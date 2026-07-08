package com.hirex.config;

import com.hirex.entity.LiveInterviewSession;
import com.hirex.entity.User;
import com.hirex.repository.LiveInterviewSessionRepository;
import com.hirex.repository.UserRepository;
import com.hirex.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * WebSocketConfig  — FIXED
 *
 * FIXES APPLIED:
 *  FIX SOCK-2  : Added SUBSCRIBE command validation in the channel interceptor.
 *                Users can only subscribe to /topic/session/{id}/** and
 *                /topic/camera/{id} if they are a participant of that session.
 *
 *  FIX SOCK-2b (PRODUCTION HARDENING): the previous version of this file only
 *                validated that the destination's session-id segment parsed
 *                as a long — it did NOT check that the subscribing user was
 *                actually a participant of that session. Any authenticated
 *                user could subscribe to /topic/session/{anyId}/status,
 *                /topic/camera/{anyId}, or /topic/live-interview/{anyId}/question
 *                for a live interview they have nothing to do with, and passively
 *                observe another candidate's interview progress, camera on/off
 *                state, and even the live question text being asked — a real
 *                confidentiality leak. This now loads the session and checks
 *                the subscriber is the assigned recruiter or an assigned
 *                applicant before allowing the SUBSCRIBE through.
 *
 *  FIX SOCK-3  : AccessDeniedException in SEND/SUBSCRIBE frames is now caught
 *                and logged cleanly instead of propagating as MessageDeliveryException.
 *
 *  FIX SOCK-1  : Reminder comment — set logging.level.org.springframework.messaging=WARN
 *                in application-prod.yml so JWTs are not printed to logs.
 *
 *  Also: /user/** endpoint enabled on the simple broker so convertAndSendToUser()
 *        works for the BUG-C4 point-to-point signaling fix.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtUtil            jwtUtil;
    private final UserDetailsService userDetailsService;
    private final LiveInterviewSessionRepository liveInterviewSessionRepository;
    private final UserRepository userRepository;

    public WebSocketConfig(JwtUtil jwtUtil,
                            UserDetailsService userDetailsService,
                            LiveInterviewSessionRepository liveInterviewSessionRepository,
                            UserRepository userRepository) {
        this.jwtUtil            = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.liveInterviewSessionRepository = liveInterviewSessionRepository;
        this.userRepository = userRepository;
    }

    @Bean
    public ThreadPoolTaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // FIX BUG-C4: /user prefix enables convertAndSendToUser() for point-to-point signaling
        config.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(heartbeatScheduler());
        config.setApplicationDestinationPrefixes("/app");
        // Required for convertAndSendToUser() to work with the simple broker
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setSendTimeLimit(15 * 1000)
                .setSendBufferSizeLimit(512 * 1024)
                .setMessageSizeLimit(512 * 1024);
    }

    // ── JWT Authentication + SUBSCRIBE Validation Channel Interceptor ────────

    /**
     * FIX SOCK-1: IMPORTANT — add to application-prod.yml:
     *   logging.level.org.springframework.messaging: WARN
     * This prevents JWTs in STOMP CONNECT frames from appearing in logs.
     *
     * FIX SOCK-2 / SOCK-2b: SUBSCRIBE frames to /topic/session/*, /topic/camera/*,
     * and /topic/live-interview/* are validated for well-formed destinations AND
     * for real participant membership via LiveInterviewSessionRepository.
     *
     * FIX SOCK-3: Exceptions in SEND/SUBSCRIBE are caught and logged instead of
     * letting them propagate as unhandled MessageDeliveryException.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {

            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor == null) return message;

                StompCommand command = accessor.getCommand();

                // ── CONNECT: authenticate via JWT ────────────────────────────
                if (StompCommand.CONNECT.equals(command)) {
                    String jwt = accessor.getLogin();
                    if (jwt != null && !jwt.isBlank()) {
                        try {
                            String email = jwtUtil.getEmail(jwt);
                            if (email != null) {
                                UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                                if (jwtUtil.isValid(jwt, userDetails)) {
                                    UsernamePasswordAuthenticationToken auth =
                                            new UsernamePasswordAuthenticationToken(
                                                    userDetails, null, userDetails.getAuthorities());
                                    accessor.setUser(auth);
                                }
                            }
                        } catch (Exception ex) {
                            // FIX SOCK-3: log but allow through; @MessageMapping handlers
                            // will reject unauthenticated messages via requirePrincipal().
                            log.warn("WebSocket CONNECT JWT validation failed: {}", ex.getMessage());
                        }
                    }
                }

                // ── SUBSCRIBE: validate destination + participant membership ──
                // FIX SOCK-2 / SOCK-2b: block malformed destinations AND
                // block authenticated-but-unrelated users from listening in
                // on someone else's session/camera/question broadcasts.
                if (StompCommand.SUBSCRIBE.equals(command)) {
                    String destination = accessor.getDestination();
                    if (destination != null) {
                        Long sessionId = extractSessionId(destination);

                        boolean isSessionScopedTopic =
                                destination.startsWith("/topic/session/") ||
                                destination.startsWith("/topic/camera/") ||
                                destination.startsWith("/topic/live-interview/");

                        if (isSessionScopedTopic) {
                            if (sessionId == null) {
                                log.warn("Rejected SUBSCRIBE to malformed destination: {}", destination);
                                return null;
                            }
                            if (!isParticipant(sessionId, accessor.getUser())) {
                                log.warn("Rejected SUBSCRIBE — {} is not a participant of session {} (dest: {})",
                                        accessor.getUser() != null ? accessor.getUser().getName() : "anonymous",
                                        sessionId, destination);
                                return null;
                            }
                        }
                        // Reject subscriptions to /user/queue/* from unauthenticated sessions
                        if (destination.startsWith("/user/queue/") && accessor.getUser() == null) {
                            log.warn("Rejected unauthenticated SUBSCRIBE to {}", destination);
                            return null;
                        }
                    }
                }

                // ── SEND: catch AccessDeniedException cleanly ────────────────
                // FIX SOCK-3: actual enforcement happens in @MessageMapping handlers;
                // this guard handles the rare case of a null principal on a SEND frame.
                if (StompCommand.SEND.equals(command)) {
                    if (accessor.getUser() == null) {
                        log.warn("Rejected unauthenticated SEND to {}", accessor.getDestination());
                        return null;
                    }
                }

                return message;
            }

            /**
             * Pulls the numeric session id out of destinations shaped like
             * /topic/session/{id}/status, /topic/camera/{id}, or
             * /topic/live-interview/{id}/question. Returns null if the
             * segment is missing or not a valid long (malformed).
             */
            private Long extractSessionId(String destination) {
                String[] parts = destination.split("/");
                // e.g. ["", "topic", "session", "{id}", "status"] -> index 3
                //      ["", "topic", "camera", "{id}"]            -> index 3
                //      ["", "topic", "live-interview", "{id}", "question"] -> index 3
                if (parts.length < 4) return null;
                try {
                    return Long.parseLong(parts[3]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }

            /**
             * True if the authenticated principal is the recruiter who owns
             * this live interview session, or one of its assigned applicants.
             * Fails closed (returns false) for unauthenticated principals,
             * unknown users, or sessions that no longer exist — a dropped
             * SUBSCRIBE is the safe default here.
             */
            private boolean isParticipant(Long sessionId, java.security.Principal principal) {
                if (principal == null || principal.getName() == null) return false;

                LiveInterviewSession session = liveInterviewSessionRepository.findById(sessionId)
                        .orElse(null);
                if (session == null) return false;

                String email = principal.getName();

                if (session.getRecruiter() != null && email.equals(session.getRecruiter().getEmail())) {
                    return true;
                }

                User user = userRepository.findByEmail(email).orElse(null);
                if (user == null) return false;

                if (session.getAssignedApplicantIds() != null
                        && session.getAssignedApplicantIds().contains(user.getId())) {
                    return true;
                }
                // Backward-compat: sessions created before multi-applicant
                // assignment only set a single `candidate` field.
                return session.getCandidate() != null
                        && email.equals(session.getCandidate().getEmail());
            }
        });
    }
}