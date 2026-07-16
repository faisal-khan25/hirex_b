package com.hirex.service;

import com.hirex.dto.LiveInterviewDto.*;
import com.hirex.dto.ProctoringDto.*;
import com.hirex.entity.*;
import com.hirex.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Transactional
public class LiveInterviewService {

    private static final Logger log = LoggerFactory.getLogger(LiveInterviewService.class);

    private static final String QUEUE_OFFER   = "/queue/offer";
    private static final String QUEUE_ANSWER  = "/queue/answer";
    private static final String QUEUE_ICE     = "/queue/ice";
    private static final String TOPIC_SESSION = "/topic/session/";
    private static final String TOPIC_CAMERA  = "/topic/camera/";
    private static final String TOPIC_MIC     = "/topic/mic/";
    private static final String TOPIC_LIVE_INTERVIEW = "/topic/live-interview/";
    private static final String TOPIC_VIOLATION = "/topic/violation/";

    private final ConcurrentHashMap<Long, Set<String>> presenceMap   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LiveInterviewSession> sessionCache = new ConcurrentHashMap<>();

    // PERFORMANCE: per-session, per-violation-type "last reported at" cache used
    // to rate-limit noisy detectors (e.g. NOISE_DETECTED firing every animation
    // frame) server-side as a defense-in-depth backstop to the client-side
    // debouncing done in useProctoring.js. Keeps DB writes and WebSocket
    // broadcast volume bounded even if a buggy/compromised client floods this
    // endpoint. Cleared when a session ends.
    private final ConcurrentHashMap<String, LocalDateTime> violationRateLimit = new ConcurrentHashMap<>();
    private static final long VIOLATION_MIN_INTERVAL_MS = 2000;

    private final LiveInterviewSessionRepository liveRepo;
    private final InterviewSessionRepository     interviewRepo;
    private final UserRepository                 userRepo;
    private final ApplicationRepository          applicationRepo;
    private final InterviewViolationRepository   violationRepo;
    private final SimpMessagingTemplate          messaging;

    // PRODUCTION FIX: previously getIceServers() only ever returned public
    // STUN servers, hardcoded. STUN alone cannot traverse symmetric NATs or
    // restrictive corporate firewalls, so a meaningful fraction of real-world
    // recruiter/candidate pairs would simply never establish a WebRTC
    // connection — with no clear error, just an indefinite "waiting" state.
    // These are optional and read from environment/config (see
    // application.properties); when unset, behavior is unchanged (STUN-only).
    @Value("${app.webrtc.turn-url:}")
    private String turnUrl;

    @Value("${app.webrtc.turn-username:}")
    private String turnUsername;

    @Value("${app.webrtc.turn-credential:}")
    private String turnCredential;

    public LiveInterviewService(LiveInterviewSessionRepository liveRepo,
                                InterviewSessionRepository interviewRepo,
                                UserRepository userRepo,
                                ApplicationRepository applicationRepo,
                                InterviewViolationRepository violationRepo,
                                SimpMessagingTemplate messaging) {
        this.liveRepo        = liveRepo;
        this.interviewRepo   = interviewRepo;
        this.userRepo        = userRepo;
        this.applicationRepo = applicationRepo;
        this.violationRepo   = violationRepo;
        this.messaging       = messaging;
    }

    // =========================================================================
    // 1.  Create session  (Recruiter)
    //
    //  UPDATED: Now accepts `assignedApplicantIds` from the request.
    //  The recruiter explicitly selects which applicants can join this session.
    //  All assigned applicant IDs are stored in the join table.
    //  If no assignedApplicantIds are provided, falls back to deriving the
    //  single candidate from the interview's application (backward-compat).
    // =========================================================================

    public LiveSessionResponse createLiveSession(CreateLiveSessionRequest request,
                                                 String recruiterEmail) {

        User recruiter = userRepo.findByEmail(recruiterEmail)
                .orElseThrow(() -> new NoSuchElementException("Recruiter not found: " + recruiterEmail));

        if (recruiter.getRole() != Role.MANAGER) {
            throw new AccessDeniedException("Only MANAGER users can create live sessions.");
        }

        // ── Resolve interview session ─────────────────────────────────────────
        // Path A: interviewSessionId provided (existing flow)
        // Path B: jobId provided without interviewSessionId — look up or auto-create
        //         an interview session for the first assigned applicant's application.
        //         This allows live interviews to be started even when no AI interview
        //         has been scheduled yet (fixes the 404 from /api/interview/application/{id}).
        InterviewSession interview;

        if (request.getInterviewSessionId() != null) {
            interview = interviewRepo.findById(request.getInterviewSessionId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "InterviewSession not found: " + request.getInterviewSessionId()));
        } else if (request.getJobId() != null && request.getAssignedApplicantIds() != null
                && !request.getAssignedApplicantIds().isEmpty()) {
            // Find the application for the first assigned applicant on this job
            Long primaryApplicantId = request.getAssignedApplicantIds().get(0);
            com.hirex.entity.Application application = applicationRepo
                    .findByJobId(request.getJobId())
                    .stream()
                    .filter(a -> a.getApplicant().getId().equals(primaryApplicantId))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException(
                            "Application not found for applicant " + primaryApplicantId
                                    + " and job " + request.getJobId()));

            // Re-use existing interview session if one exists, otherwise create one
            interview = interviewRepo.findByApplicationId(application.getId())
                    .orElseGet(() -> {
                        InterviewSession newSession = new InterviewSession(
                                application,
                                com.hirex.entity.InterviewType.AI,
                                "DEFAULT_AI_INTERVIEW");
                        newSession.setScheduledAt(java.time.LocalDateTime.now());
                        newSession.setMaxDurationMinutes(60);
                        newSession.setStatus(com.hirex.entity.InterviewStatus.PENDING);
                        newSession.setCandidateName(application.getApplicant().getName());
                        newSession.setPositionTitle(application.getJob().getTitle());
                        newSession.setJobDescription(application.getJob().getDescription());
                        return interviewRepo.save(newSession);
                    });
        } else {
            throw new IllegalArgumentException(
                    "Either interviewSessionId or (jobId + assignedApplicantIds) must be provided.");
        }

        // ── Resolve assigned applicants ───────────────────────────────────
        // Priority 1: explicit assignedApplicantIds from recruiter UI
        // Priority 2: fallback — derive from interview.application.applicant
        Set<Long> assignedIds = new HashSet<>();

        if (request.getAssignedApplicantIds() != null && !request.getAssignedApplicantIds().isEmpty()) {
            // Validate all provided IDs exist as JOBSEEKER users
            for (Long applicantId : request.getAssignedApplicantIds()) {
                User applicant = userRepo.findById(applicantId)
                        .orElseThrow(() -> new NoSuchElementException(
                                "Applicant user not found: " + applicantId));
                if (applicant.getRole() != Role.JOBSEEKER) {
                    throw new IllegalArgumentException(
                            "User " + applicantId + " is not a JOBSEEKER.");
                }
                assignedIds.add(applicantId);
            }
            log.debug("Recruiter explicitly assigned {} applicant(s): {}", assignedIds.size(), assignedIds);
        } else {
            // Backward-compat: derive from the interview's application
            User derivedCandidate = interview.getApplication().getApplicant();
            if (derivedCandidate == null) {
                throw new NoSuchElementException(
                        "No applicant linked to interview " + request.getInterviewSessionId());
            }
            assignedIds.add(derivedCandidate.getId());
            log.debug("No assignedApplicantIds provided — falling back to candidateId={} from interview",
                    derivedCandidate.getId());
        }

        // ── The primary candidate for WebRTC signaling ─────────────────────
        // When only one applicant is assigned, use them. When multiple are
        // assigned, use the interview's application's applicant as the primary
        // candidate (they will all see the join button but WebRTC is 1-on-1).
        User primaryCandidate = interview.getApplication().getApplicant();
        if (primaryCandidate == null || !assignedIds.contains(primaryCandidate.getId())) {
            // Primary candidate is whoever is the first in the assigned set
            Long firstId = assignedIds.iterator().next();
            primaryCandidate = userRepo.findById(firstId)
                    .orElseThrow(() -> new NoSuchElementException("Applicant not found: " + firstId));
        }

        // ── Prevent duplicate active sessions ─────────────────────────────
        liveRepo.findByInterviewSessionId(interview.getId()).ifPresent(existing -> {
            if (existing.getSessionStatus() == LiveSessionStatus.WAITING
                    || existing.getSessionStatus() == LiveSessionStatus.ACTIVE) {
                throw new IllegalStateException(
                        "A live session is already active for interview " + interview.getId());
            }
        });

        String token = UUID.randomUUID().toString().replace("-", "");

        LiveInterviewSession session = new LiveInterviewSession(
                interview, recruiter, primaryCandidate, token, assignedIds);
        liveRepo.save(session);

        log.info("Live session {} created for interview {} by recruiter {} — assigned applicants: {}",
                session.getId(), interview.getId(), recruiterEmail, assignedIds);

        // ── Push real-time invite to ALL assigned applicants via WebSocket ─
        pushInviteToAssignedApplicants(session, interview);

        return toFullResponse(session);
    }

    // =========================================================================
    // 1b.  Generate (or reuse) an interview link for ONE shortlisted candidate,
    //      triggered from inside a chat conversation ("Generate Interview Link"
    //      button). Unlike createLiveSession() above — which supports the
    //      recruiter picking multiple applicants from a picker UI — this is the
    //      simple 1-on-1 chat-link flow:
    //        - exactly one candidate (the one in this conversation)
    //        - no popup
    //        - reuses an existing WAITING/ACTIVE session instead of erroring
    //          out, so clicking the button twice just re-sends the same link.
    // =========================================================================

    public LiveSessionResponse generateInterviewLinkForApplication(Long applicationId,
                                                                   String recruiterEmail) {

        User recruiter = userRepo.findByEmail(recruiterEmail)
                .orElseThrow(() -> new NoSuchElementException("Recruiter not found: " + recruiterEmail));

        if (recruiter.getRole() != Role.MANAGER) {
            throw new AccessDeniedException("Only MANAGER users can generate interview links.");
        }

        com.hirex.entity.Application application = applicationRepo.findById(applicationId)
                .orElseThrow(() -> new NoSuchElementException("Application not found: " + applicationId));

        boolean ownsJob = application.getJob() != null
                && application.getJob().getCompany() != null
                && application.getJob().getCompany().getManager() != null
                && application.getJob().getCompany().getManager().getId().equals(recruiter.getId());
        if (!ownsJob) {
            throw new AccessDeniedException("You are not the recruiter assigned to this application.");
        }

        if (application.getStatus() != com.hirex.entity.ApplicationStatus.SHORTLISTED) {
            throw new IllegalStateException(
                    "An interview link can only be generated for a SHORTLISTED candidate.");
        }

        User candidate = application.getApplicant();
        if (candidate == null) {
            throw new NoSuchElementException("No applicant linked to application " + applicationId);
        }

        // Re-use (or lazily create) the underlying AI/scheduled interview session
        InterviewSession interview = interviewRepo.findByApplicationId(applicationId)
                .orElseGet(() -> {
                    InterviewSession newSession = new InterviewSession(
                            application,
                            com.hirex.entity.InterviewType.AI,
                            "DEFAULT_AI_INTERVIEW");
                    newSession.setScheduledAt(LocalDateTime.now());
                    newSession.setMaxDurationMinutes(60);
                    newSession.setStatus(com.hirex.entity.InterviewStatus.PENDING);
                    newSession.setCandidateName(candidate.getName());
                    newSession.setPositionTitle(application.getJob().getTitle());
                    newSession.setJobDescription(application.getJob().getDescription());
                    return interviewRepo.save(newSession);
                });

        // Re-use an existing, still-valid live session instead of failing —
        // clicking "Generate Interview Link" again should just re-share the link.
        Optional<LiveInterviewSession> existing = liveRepo.findByInterviewSessionId(interview.getId());
        if (existing.isPresent()) {
            LiveInterviewSession s = existing.get();
            boolean stillOpen = (s.getSessionStatus() == LiveSessionStatus.WAITING
                    || s.getSessionStatus() == LiveSessionStatus.ACTIVE)
                    && LocalDateTime.now().isBefore(s.getTokenExpiresAt());
            if (stillOpen) {
                log.info("Re-using existing live session {} for application {}", s.getId(), applicationId);
                return toFullResponse(s);
            }
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        Set<Long> assignedIds = new HashSet<>(Set.of(candidate.getId()));

        LiveInterviewSession session = new LiveInterviewSession(
                interview, recruiter, candidate, token, assignedIds);
        liveRepo.save(session);

        log.info("Interview link generated: session {} for application {} (candidate {}) by recruiter {}",
                session.getId(), applicationId, candidate.getId(), recruiterEmail);

        pushInviteToAssignedApplicants(session, interview);

        return toFullResponse(session);
    }

    // =========================================================================
    // 2.  Get session by token  (used by both participants on join)
    //     UPDATED: assertParticipantOrAssigned checks assignedApplicantIds too
    // =========================================================================

    @Transactional(readOnly = true)
    public LiveSessionResponse getSessionByToken(String token, String userEmail) {
        LiveInterviewSession session = requireActiveSession(token);
        assertParticipantOrAssigned(session, userEmail);
        return toFullResponse(session);
    }

    // =========================================================================
    // 3.  Get session by DB id  (recruiter re-opens panel)
    // =========================================================================

    @Transactional(readOnly = true)
    public LiveSessionSummaryResponse getSessionById(Long id, String userEmail) {
        LiveInterviewSession session = liveRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Live session not found: " + id));
        assertParticipantOrAssigned(session, userEmail);
        return toSummaryResponse(session);
    }

    // =========================================================================
    // 3b. Candidate polls for an active live session on their application
    //     UPDATED: uses findActiveForAssignedCandidate — checks assignedApplicantIds
    //
    //     Only returns a token if the logged-in user's ID is in the assigned set.
    //     This is the gate that controls the "Join Live Interview" button.
    // =========================================================================

    @Transactional(readOnly = true)
    public Optional<String> getActiveSessionForCandidate(Long applicationId,
                                                         String candidateEmail) {
        User candidate = userRepo.findByEmail(candidateEmail)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + candidateEmail));

        // SECURITY: Only return a token if this user's ID is in assignedApplicantIds
        return liveRepo.findActiveForAssignedCandidate(
                candidate.getId(),
                applicationId,
                List.of(LiveSessionStatus.WAITING, LiveSessionStatus.ACTIVE),
                LocalDateTime.now()
        ).map(LiveInterviewSession::getSessionToken);
    }

    // =========================================================================
    // 4.  Participant joins — activates ONLY when BOTH are present
    //     UPDATED: assertParticipantOrAssigned allows any assigned applicant to join
    // =========================================================================

    public void onParticipantJoin(JoinSessionMessage msg, String userEmail) {
        LiveInterviewSession session = requireActiveSession(msg.getSessionToken());
        assertParticipantOrAssigned(session, userEmail);

        boolean isCandidate = isAssignedApplicant(session, userEmail);
        boolean isRecruiter = session.getRecruiter().getEmail().equals(userEmail);
        String claimedRole  = msg.getRole();

        if (isCandidate && !"CANDIDATE".equals(claimedRole)) {
            throw new AccessDeniedException("Role mismatch: you are the CANDIDATE for this session.");
        }
        if (isRecruiter && !"RECRUITER".equals(claimedRole)) {
            throw new AccessDeniedException("Role mismatch: you are the RECRUITER for this session.");
        }

        Set<String> joined = presenceMap.computeIfAbsent(
                session.getId(), id -> ConcurrentHashMap.newKeySet());
        joined.add(userEmail);

        log.info("Session {} — {} joined. Present: {}", session.getId(), userEmail, joined.size());

        sessionCache.put(msg.getSessionToken(), session);

        if (joined.size() >= 2 && session.getSessionStatus() == LiveSessionStatus.WAITING) {
            session.setSessionStatus(LiveSessionStatus.ACTIVE);
            session.setInterviewStartTime(LocalDateTime.now());
            liveRepo.save(session);

            log.info("Session {} is now ACTIVE — both participants connected.", session.getId());
            broadcastSessionStatus(session, "Interview started – both participants connected.");
        }
    }

    // =========================================================================
    // 5.  Camera status update — BOTH candidate AND recruiter
    // =========================================================================

    public void updateCameraStatus(CameraStatusMessage msg, String userEmail) {
        LiveInterviewSession session = requireActiveSession(msg.getSessionToken());
        assertParticipantOrAssigned(session, userEmail);

        boolean isCandidate = isAssignedApplicant(session, userEmail);
        boolean isRecruiter = session.getRecruiter().getEmail().equals(userEmail);

        if (isCandidate && !"CANDIDATE".equals(msg.getRole())) {
            throw new AccessDeniedException("Role mismatch for camera update.");
        }
        if (isRecruiter && !"RECRUITER".equals(msg.getRole())) {
            throw new AccessDeniedException("Role mismatch for camera update.");
        }

        int updatedOffCount;

        if (isCandidate) {
            boolean wasEnabled = session.isCameraEnabled();
            session.setCameraEnabled(msg.isCameraEnabled());
            if (wasEnabled && !msg.isCameraEnabled()) {
                session.setCameraOffCount(session.getCameraOffCount() + 1);
                session.setCameraDisabledAt(LocalDateTime.now());
            }
            updatedOffCount = session.getCameraOffCount();
        } else {
            boolean wasEnabled = session.isRecruiterCameraEnabled();
            session.setRecruiterCameraEnabled(msg.isCameraEnabled());
            if (wasEnabled && !msg.isCameraEnabled()) {
                session.setRecruiterCameraOffCount(session.getRecruiterCameraOffCount() + 1);
                session.setRecruiterCameraDisabledAt(LocalDateTime.now());
            }
            updatedOffCount = session.getRecruiterCameraOffCount();
        }

        liveRepo.save(session);
        sessionCache.put(msg.getSessionToken(), session);

        CameraStatusNotification notification = new CameraStatusNotification();
        notification.setLiveSessionId(session.getId());
        notification.setParticipantRole(msg.getRole());
        notification.setCameraEnabled(msg.isCameraEnabled());
        notification.setReason(msg.getReason());
        notification.setCameraOffCount(updatedOffCount);
        notification.setTimestamp(LocalDateTime.now());

        messaging.convertAndSend(TOPIC_CAMERA + session.getId(), notification);
    }

    // =========================================================================
    // 5b.  Microphone status update — BOTH candidate AND recruiter
    //      Mirrors updateCameraStatus(); no off-count/persistence needed —
    //      this is a live-only UI signal broadcast to /topic/mic/{id}.
    // =========================================================================

    public void updateMicStatus(MicStatusMessage msg, String userEmail) {
        LiveInterviewSession session = requireActiveSessionCached(msg.getSessionToken());
        assertParticipantOrAssigned(session, userEmail);

        boolean isCandidate = isAssignedApplicant(session, userEmail);
        boolean isRecruiter = session.getRecruiter().getEmail().equals(userEmail);

        if (isCandidate && !"CANDIDATE".equals(msg.getRole())) {
            throw new AccessDeniedException("Role mismatch for microphone update.");
        }
        if (isRecruiter && !"RECRUITER".equals(msg.getRole())) {
            throw new AccessDeniedException("Role mismatch for microphone update.");
        }

        MicStatusNotification notification = new MicStatusNotification();
        notification.setLiveSessionId(session.getId());
        notification.setParticipantRole(msg.getRole());
        notification.setMicEnabled(msg.isMicEnabled());
        notification.setReason(msg.getReason());
        notification.setTimestamp(LocalDateTime.now());

        messaging.convertAndSend(TOPIC_MIC + session.getId(), notification);
    }

    // =========================================================================
    // 5c. NEW — AI Interview Monitoring: real-time violation reporting
    //
    //     Called from /app/live/violation. The candidate's browser runs all
    //     detection client-side (phone/face/multi-face/noise/tab-switch) for
    //     real-time performance, then reports each violation here. The server:
    //       1. Verifies the sender is the assigned candidate on an active session.
    //       2. Rate-limits per (session, type) to absorb noisy detectors.
    //       3. Clamps severity server-side (defense-in-depth — a compromised
    //          client can't downgrade a CRITICAL phone-detection to LOW).
    //       4. Persists the violation for the post-interview log.
    //       5. Broadcasts to /topic/violation/{liveSessionId} so BOTH the
    //          candidate (live warning banner) and recruiter (live feed) see
    //          it instantly, without polling.
    // =========================================================================

    public void recordViolation(ViolationReportMessage msg, String userEmail) {
        LiveInterviewSession session = requireActiveSessionCached(msg.getSessionToken());
        assertParticipantOrAssigned(session, userEmail);

        // Only the candidate is monitored — a recruiter-side report is ignored
        // (recruiters aren't proctored) but does not error, in case a future
        // client build sends a CAMERA_OFF/MICROPHONE_OFF event from either side.
        boolean isCandidate = isAssignedApplicant(session, userEmail);
        if (!isCandidate) {
            log.debug("Ignoring violation report from non-candidate {} on session {}",
                    userEmail, session.getId());
            return;
        }

        if (msg.getViolationType() == null) return;

        String rateLimitKey = session.getId() + ":" + msg.getViolationType();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = violationRateLimit.get(rateLimitKey);
        if (last != null && java.time.Duration.between(last, now).toMillis() < VIOLATION_MIN_INTERVAL_MS) {
            return; // swallow — client is already debouncing, this is just a backstop
        }
        violationRateLimit.put(rateLimitKey, now);

        ViolationSeverity severity = clampSeverity(msg.getViolationType(), msg.getSeverity());
        String message = (msg.getMessage() != null && !msg.getMessage().isBlank())
                ? msg.getMessage()
                : defaultMessageFor(msg.getViolationType());

        InterviewViolation violation = new InterviewViolation(
                session.getId(), msg.getViolationType(), severity, message, msg.getMetadata());
        violation = violationRepo.save(violation);

        ViolationNotification note = new ViolationNotification();
        note.setId(violation.getId());
        note.setLiveSessionId(session.getId());
        note.setViolationType(violation.getViolationType());
        note.setSeverity(violation.getSeverity());
        note.setMessage(violation.getMessage());
        note.setTimestamp(violation.getCreatedAt());

        messaging.convertAndSend(TOPIC_VIOLATION + session.getId(), note);
        log.info("Violation recorded — session={}, type={}, severity={}",
                session.getId(), violation.getViolationType(), violation.getSeverity());
    }

    /** Server-side floor/ceiling per violation type — never trust client severity for CRITICAL cases. */
    private ViolationSeverity clampSeverity(ViolationType type, ViolationSeverity clientSeverity) {
        ViolationSeverity floor = defaultSeverityFor(type);
        if (type == ViolationType.PHONE_DETECTED) {
            return ViolationSeverity.CRITICAL; // never downgradable
        }
        if (clientSeverity == null) return floor;
        // Allow the client to escalate (e.g. FACE_ABSENCE_PROLONGED) but not
        // fall below the type's floor.
        return clientSeverity.ordinal() < floor.ordinal() ? floor : clientSeverity;
    }

    private ViolationSeverity defaultSeverityFor(ViolationType type) {
        switch (type) {
            case PHONE_DETECTED:            return ViolationSeverity.CRITICAL;
            case MULTIPLE_FACES_DETECTED:   return ViolationSeverity.HIGH;
            case FACE_ABSENCE_PROLONGED:    return ViolationSeverity.HIGH;
            case NO_FACE_DETECTED:          return ViolationSeverity.MEDIUM;
            case TAB_SWITCH:                return ViolationSeverity.MEDIUM;
            case CAMERA_OFF:                return ViolationSeverity.MEDIUM;
            case MICROPHONE_OFF:            return ViolationSeverity.LOW;
            case NOISE_DETECTED:            return ViolationSeverity.LOW;
            default:                        return ViolationSeverity.LOW;
        }
    }

    private String defaultMessageFor(ViolationType type) {
        switch (type) {
            case PHONE_DETECTED:            return "A mobile phone was detected in the camera view.";
            case MULTIPLE_FACES_DETECTED:   return "Multiple faces detected in the camera view.";
            case FACE_ABSENCE_PROLONGED:    return "Candidate's face has been absent for a prolonged period.";
            case NO_FACE_DETECTED:          return "No face detected in the camera view.";
            case TAB_SWITCH:                return "Candidate switched away from the interview tab.";
            case CAMERA_OFF:                return "Candidate's camera was turned off.";
            case MICROPHONE_OFF:            return "Candidate's microphone was muted.";
            case NOISE_DETECTED:            return "Background noise detected above the allowed threshold.";
            default:                        return "Proctoring violation detected.";
        }
    }

    // =========================================================================
    // 5d. NEW — AI Interview Monitoring: fetch the full violation log
    //     GET /api/live-interview/{id}/violations — MANAGER (recruiter) only.
    // =========================================================================

    @Transactional(readOnly = true)
    public ViolationLogResponse getViolationLog(Long liveSessionId, String recruiterEmail) {
        LiveInterviewSession session = liveRepo.findById(liveSessionId)
                .orElseThrow(() -> new NoSuchElementException("Live session not found: " + liveSessionId));

        if (!session.getRecruiter().getEmail().equals(recruiterEmail)) {
            throw new AccessDeniedException("Only the assigned recruiter can view the violation log.");
        }

        List<InterviewViolation> violations = violationRepo.findByLiveSessionIdOrderByCreatedAtAsc(liveSessionId);

        ViolationLogResponse response = new ViolationLogResponse();
        response.setLiveSessionId(liveSessionId);
        response.setTotalCount(violations.size());
        response.setCriticalCount(violations.stream().filter(v -> v.getSeverity() == ViolationSeverity.CRITICAL).count());
        response.setHighCount(violations.stream().filter(v -> v.getSeverity() == ViolationSeverity.HIGH).count());
        response.setMediumCount(violations.stream().filter(v -> v.getSeverity() == ViolationSeverity.MEDIUM).count());
        response.setLowCount(violations.stream().filter(v -> v.getSeverity() == ViolationSeverity.LOW).count());
        response.setEntries(violations.stream().map(v -> {
            ViolationLogEntry entry = new ViolationLogEntry();
            entry.setId(v.getId());
            entry.setViolationType(v.getViolationType());
            entry.setSeverity(v.getSeverity());
            entry.setMessage(v.getMessage());
            entry.setMetadata(v.getMetadata());
            entry.setTimestamp(v.getCreatedAt());
            return entry;
        }).collect(Collectors.toList()));

        return response;
    }

    // =========================================================================
    // 6.  WebRTC Signaling — point-to-point relay (UNCHANGED)
    // =========================================================================

    public void relayOffer(WebRtcOfferMessage msg, String senderEmail) {
        LiveInterviewSession session = requireActiveSessionCached(msg.getSessionToken());
        assertParticipantOrAssigned(session, senderEmail);

        if (!isAssignedApplicant(session, senderEmail)) {
            throw new AccessDeniedException("Only the CANDIDATE can send the WebRTC offer.");
        }

        String targetEmail = session.getRecruiter().getEmail();
        messaging.convertAndSendToUser(targetEmail, QUEUE_OFFER, msg);
        log.debug("Relayed offer from {} to {}", senderEmail, targetEmail);
    }

    public void relayAnswer(WebRtcAnswerMessage msg, String senderEmail) {
        LiveInterviewSession session = requireActiveSessionCached(msg.getSessionToken());
        assertParticipantOrAssigned(session, senderEmail);

        if (!session.getRecruiter().getEmail().equals(senderEmail)) {
            throw new AccessDeniedException("Only the RECRUITER can send the WebRTC answer.");
        }

        String targetEmail = session.getCandidate().getEmail();
        messaging.convertAndSendToUser(targetEmail, QUEUE_ANSWER, msg);
        log.debug("Relayed answer from {} to {}", senderEmail, targetEmail);
    }

    public void relayIceCandidate(IceCandidateMessage msg, String senderEmail) {
        LiveInterviewSession session = requireActiveSessionCached(msg.getSessionToken());
        assertParticipantOrAssigned(session, senderEmail);

        String targetEmail = isAssignedApplicant(session, senderEmail)
                ? session.getRecruiter().getEmail()
                : session.getCandidate().getEmail();

        messaging.convertAndSendToUser(targetEmail, QUEUE_ICE, msg);
    }

    // =========================================================================
    // 7.  End interview  (Recruiter)
    // =========================================================================

    public LiveSessionResponse endInterview(Long liveSessionId,
                                            EndInterviewRequest request,
                                            String recruiterEmail) {

        LiveInterviewSession session = liveRepo.findById(liveSessionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "LiveInterviewSession not found: " + liveSessionId));

        if (!session.getSessionToken().equals(request.getSessionToken())) {
            throw new AccessDeniedException("Invalid session token.");
        }

        if (!session.getRecruiter().getEmail().equals(recruiterEmail)) {
            throw new AccessDeniedException("Only the assigned recruiter can end this session.");
        }

        session.setSessionStatus(LiveSessionStatus.ENDED);
        session.setInterviewEndTime(LocalDateTime.now());

        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            session.setRecruiterNotes(request.getNotes());
        }

        liveRepo.save(session);

        presenceMap.remove(session.getId());
        sessionCache.remove(session.getSessionToken());
        clearViolationRateLimit(session.getId());

        log.info("Session {} ended by recruiter {}", session.getId(), recruiterEmail);
        broadcastSessionStatus(session, "Interview ended by recruiter.");

        return toFullResponse(session);
    }

    // =========================================================================
    // 8.  Candidate disconnect — wired via WebSocketEventListener
    // =========================================================================

    @Transactional
    public void onParticipantDisconnect(String sessionToken, String userEmail) {
        liveRepo.findBySessionToken(sessionToken).ifPresent(session -> {
            if (session.getSessionStatus() == LiveSessionStatus.ACTIVE
                    || session.getSessionStatus() == LiveSessionStatus.WAITING) {

                boolean isCandidateSide = isAssignedApplicant(session, userEmail);

                if (isCandidateSide) {
                    session.setSessionStatus(LiveSessionStatus.ABANDONED);
                    if (session.isCameraEnabled()) {
                        session.setCameraEnabled(false);
                        session.setCameraOffCount(session.getCameraOffCount() + 1);
                        session.setCameraDisabledAt(LocalDateTime.now());
                    }
                    liveRepo.save(session);

                    presenceMap.remove(session.getId());
                    sessionCache.remove(sessionToken);

                    log.info("Session {} ABANDONED — candidate {} disconnected.",
                            session.getId(), userEmail);
                    broadcastSessionStatus(session, "Candidate disconnected unexpectedly.");

                } else {
                    Set<String> joined = presenceMap.get(session.getId());
                    if (joined != null) joined.remove(userEmail);

                    log.info("Session {} — recruiter {} disconnected.", session.getId(), userEmail);
                    broadcastSessionStatus(session, "Recruiter disconnected — waiting for reconnect.");
                }
            }
        });
    }

    // =========================================================================
    // 9.  ICE server configuration endpoint
    // =========================================================================

    @Transactional(readOnly = true)
    public List<IceServerConfig> getIceServers() {
        List<IceServerConfig> servers = new ArrayList<>(List.of(
                new IceServerConfig("stun:stun.l.google.com:19302"),
                new IceServerConfig("stun:stun1.l.google.com:19302")
        ));

        // PRODUCTION FIX: append a TURN server when one is configured so
        // connections behind symmetric NATs/restrictive firewalls can still
        // relay media instead of failing silently. See app.webrtc.turn-* in
        // application.properties.
        if (turnUrl != null && !turnUrl.isBlank()) {
            IceServerConfig turn = new IceServerConfig(turnUrl);
            turn.setUsername(turnUsername);
            turn.setCredential(turnCredential);
            servers.add(turn);
        }

        return servers;
    }

    // =========================================================================
    // 10. Get assignable applicants for a given interview session
    //     Called by the recruiter UI before creating a live session so the
    //     recruiter can pick which applicants to assign.
    //     Returns all shortlisted applicants for the same job.
    // =========================================================================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAssignableApplicants(Long interviewSessionId,
                                                             String recruiterEmail) {
        User recruiter = userRepo.findByEmail(recruiterEmail)
                .orElseThrow(() -> new NoSuchElementException("Recruiter not found: " + recruiterEmail));

        if (recruiter.getRole() != Role.MANAGER) {
            throw new AccessDeniedException("Only MANAGER users can fetch assignable applicants.");
        }

        InterviewSession interview = interviewRepo.findById(interviewSessionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "InterviewSession not found: " + interviewSessionId));

        Long jobId = interview.getApplication().getJob().getId();

        return getShortlistedApplicantsForJob(jobId);
    }

    // =========================================================================
    // 11. Get assignable applicants directly by jobId (no AI session required)
    //     This is the primary entry point from the recruiter chat panel —
    //     the recruiter may not have run AI interviews yet, but still wants
    //     to start a live video interview.
    // =========================================================================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAssignableApplicantsByJob(Long jobId,
                                                                  String recruiterEmail) {
        User recruiter = userRepo.findByEmail(recruiterEmail)
                .orElseThrow(() -> new NoSuchElementException("Recruiter not found: " + recruiterEmail));

        if (recruiter.getRole() != Role.MANAGER) {
            throw new AccessDeniedException("Only MANAGER users can fetch assignable applicants.");
        }

        return getShortlistedApplicantsForJob(jobId);
    }

    /** Shared helper: map shortlisted applications for a job to picker DTOs. */
    private List<Map<String, Object>> getShortlistedApplicantsForJob(Long jobId) {
        // Fetch both SHORTLISTED (by status) applicants for the job
        List<com.hirex.entity.Application> applications = applicationRepo.findByJobId(jobId)
                .stream()
                .filter(a -> a.getStatus() == com.hirex.entity.ApplicationStatus.SHORTLISTED
                        || a.getStatus() == com.hirex.entity.ApplicationStatus.HIRED)
                .collect(java.util.stream.Collectors.toList());

        return applications.stream()
                .map(app -> {
                    Map<String, Object> info = new java.util.LinkedHashMap<>();
                    info.put("applicantId", app.getApplicant().getId());
                    info.put("name", app.getApplicant().getName());
                    info.put("email", app.getApplicant().getEmail());
                    info.put("applicationId", app.getId());
                    return info;
                })
                .collect(java.util.stream.Collectors.toList());
    }



    // =========================================================================
    // 12. NEW — Live Broadcasting for AI Interview
    // =========================================================================

    public LiveSessionResponse startBroadcastForAIInterview(Long interviewSessionId) {
        InterviewSession interview = interviewRepo.findById(interviewSessionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "InterviewSession not found: " + interviewSessionId));

        Optional<LiveInterviewSession> existing = liveRepo.findByInterviewSessionId(interviewSessionId);
        if (existing.isPresent()) {
            LiveInterviewSession s = existing.get();
            boolean stillOpen = (s.getSessionStatus() == LiveSessionStatus.WAITING
                    || s.getSessionStatus() == LiveSessionStatus.ACTIVE)
                    && LocalDateTime.now().isBefore(s.getTokenExpiresAt());
            if (stillOpen) {
                log.info("Live broadcast already open for interview {} (liveSessionId={}) — reusing.",
                        interviewSessionId, s.getId());
                return toFullResponse(s);
            }
        }

        com.hirex.entity.Application application = interview.getApplication();
        if (application == null) {
            throw new NoSuchElementException("Interview " + interviewSessionId + " has no linked application.");
        }

        User candidate = application.getApplicant();
        if (candidate == null) {
            throw new NoSuchElementException("No applicant linked to interview " + interviewSessionId);
        }

        User recruiter = Optional.ofNullable(application.getJob())
                .map(com.hirex.entity.Job::getCompany)
                .map(com.hirex.entity.Company::getManager)
                .orElseThrow(() -> new NoSuchElementException(
                        "No recruiter/manager found for the job linked to interview " + interviewSessionId));

        String token = UUID.randomUUID().toString().replace("-", "");
        Set<Long> assignedIds = new HashSet<>(Set.of(candidate.getId()));

        LiveInterviewSession session = new LiveInterviewSession(
                interview, recruiter, candidate, token, assignedIds);
        session.setSessionOrigin(LiveSessionOrigin.AI_INTERVIEW);
        liveRepo.save(session);

        log.info("Live broadcast auto-started for AI interview {} — liveSessionId={}, candidate={}, recruiter={}",
                interviewSessionId, session.getId(), candidate.getEmail(), recruiter.getEmail());

        pushInviteToAssignedApplicants(session, interview);

        return toFullResponse(session);
    }

    @Transactional
    public void updateCurrentQuestion(Long interviewSessionId, int questionNumber,
                                      String questionText, int totalQuestions) {
        Optional<LiveInterviewSession> maybeSession = liveRepo.findByInterviewSessionId(interviewSessionId);
        if (maybeSession.isEmpty()) {
            log.debug("No live broadcast session for interview {} — skipping question push.", interviewSessionId);
            return;
        }

        LiveInterviewSession session = maybeSession.get();
        if (session.getSessionStatus() == LiveSessionStatus.ENDED
                || session.getSessionStatus() == LiveSessionStatus.ABANDONED) {
            return;
        }

        session.setCurrentQuestionNumber(questionNumber);
        session.setCurrentQuestionText(questionText);
        session.setTotalQuestions(totalQuestions);
        liveRepo.save(session);

        QuestionUpdateNotification note = new QuestionUpdateNotification();
        note.setLiveSessionId(session.getId());
        note.setInterviewSessionId(interviewSessionId);
        note.setQuestionNumber(questionNumber);
        note.setQuestionText(questionText);
        note.setTotalQuestions(totalQuestions);
        note.setTimestamp(LocalDateTime.now());

        messaging.convertAndSend(TOPIC_LIVE_INTERVIEW + session.getId() + "/question", note);
        log.debug("Pushed question {}/{} to live broadcast {} (interview {})",
                questionNumber, totalQuestions, session.getId(), interviewSessionId);
    }

    @Transactional
    public void endBroadcastForAIInterview(Long interviewSessionId) {
        Optional<LiveInterviewSession> maybeSession = liveRepo.findByInterviewSessionId(interviewSessionId);
        if (maybeSession.isEmpty()) {
            return;
        }

        LiveInterviewSession session = maybeSession.get();
        if (session.getSessionStatus() == LiveSessionStatus.ENDED) {
            return;
        }

        session.setSessionStatus(LiveSessionStatus.ENDED);
        session.setInterviewEndTime(LocalDateTime.now());
        liveRepo.save(session);

        presenceMap.remove(session.getId());
        sessionCache.remove(session.getSessionToken());
        clearViolationRateLimit(session.getId());

        log.info("Live broadcast {} auto-ended — AI interview {} completed.", session.getId(), interviewSessionId);
        broadcastSessionStatus(session, "Interview completed — live broadcast ended.");
    }

    @Transactional(readOnly = true)
    public LiveSessionSummaryResponse getBroadcastByInterviewSession(Long interviewSessionId, String userEmail) {
        LiveInterviewSession session = liveRepo.findByInterviewSessionId(interviewSessionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No live broadcast session found for interview " + interviewSessionId));
        assertParticipantOrAssigned(session, userEmail);
        // GLUE FIX: only the recruiter needs (and is allowed) the raw token here —
        // the candidate already receives it via /candidate/{applicationId}.
        boolean isRecruiter = session.getRecruiter().getEmail().equals(userEmail);
        return toSummaryResponse(session, isRecruiter);
    }

    @Transactional(readOnly = true)
    public List<LiveSessionSummaryResponse> getActiveBroadcastsForRecruiter(String recruiterEmail) {
        User recruiter = userRepo.findByEmail(recruiterEmail)
                .orElseThrow(() -> new NoSuchElementException("Recruiter not found: " + recruiterEmail));

        if (recruiter.getRole() != Role.MANAGER) {
            throw new AccessDeniedException("Only MANAGER users can view live broadcasts.");
        }

        List<LiveInterviewSession> sessions = liveRepo.findByRecruiterIdAndSessionStatusInOrderByCreatedAtDesc(
                recruiter.getId(), List.of(LiveSessionStatus.WAITING, LiveSessionStatus.ACTIVE));

        // GLUE FIX: this endpoint is MANAGER-only (checked above) and every
        // session belongs to the calling recruiter, so it's always safe to
        // include the token the dashboard needs for WebRTC/STOMP signaling.
        return sessions.stream().map(s -> toSummaryResponse(s, true)).collect(Collectors.toList());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Push a LIVE_INVITE WebSocket notification to every assigned applicant.
     * Each applicant receives it at /user/queue/live-interview-invite.
     */
    private void pushInviteToAssignedApplicants(LiveInterviewSession session,
                                                InterviewSession interview) {
        LiveInterviewNotification notification = new LiveInterviewNotification();
        notification.setLiveSessionId(session.getId());
        notification.setSessionToken(session.getSessionToken());
        notification.setRecruiterName(session.getRecruiter().getName());
        notification.setType("INVITE");
        notification.setTimestamp(LocalDateTime.now());

        // Try to set company and job title from the interview
        try {
            var application = interview.getApplication();
            if (application != null && application.getJob() != null) {
                var job = application.getJob();
                notification.setJobTitle(job.getTitle());
                if (job.getCompany() != null) {
                    notification.setCompanyName(job.getCompany().getName());
                }
            }
        } catch (Exception e) {
            log.warn("Could not enrich notification with job/company data: {}", e.getMessage());
        }

        for (Long applicantId : session.getAssignedApplicantIds()) {
            try {
                userRepo.findById(applicantId).ifPresent(applicant -> {
                    messaging.convertAndSendToUser(
                            applicant.getEmail(),
                            "/queue/live-interview-invite",
                            notification
                    );
                    log.debug("Sent live-interview invite to applicant {} ({})",
                            applicantId, applicant.getEmail());
                });
            } catch (Exception e) {
                log.warn("Failed to push invite to applicantId={}: {}", applicantId, e.getMessage());
            }
        }
    }

    /** Drops every rate-limit entry for a session once it ends, so the map doesn't grow unbounded. */
    private void clearViolationRateLimit(Long liveSessionId) {
        String prefix = liveSessionId + ":";
        violationRateLimit.keySet().removeIf(k -> k.startsWith(prefix));
    }

    private LiveInterviewSession requireActiveSession(String token) {
        LiveInterviewSession session = liveRepo.findBySessionToken(token)
                .orElseThrow(() -> new NoSuchElementException(
                        "Live session not found for the provided token."));

        if (session.getSessionStatus() == LiveSessionStatus.ENDED) {
            throw new AccessDeniedException("ENDED: This interview has already been completed.");
        }
        if (LocalDateTime.now().isAfter(session.getTokenExpiresAt())) {
            throw new AccessDeniedException("EXPIRED: This interview link has expired.");
        }
        return session;
    }

    private LiveInterviewSession requireActiveSessionCached(String token) {
        LiveInterviewSession cached = sessionCache.get(token);
        if (cached != null
                && cached.getSessionStatus() != LiveSessionStatus.ENDED
                && LocalDateTime.now().isBefore(cached.getTokenExpiresAt())) {
            return cached;
        }
        LiveInterviewSession session = requireActiveSession(token);
        sessionCache.put(token, session);
        return session;
    }

    /**
     * UPDATED: Allows access if:
     *  - user is the recruiter, OR
     *  - user's ID is in the assignedApplicantIds set (any assigned applicant)
     */
    private void assertParticipantOrAssigned(LiveInterviewSession session, String email) {
        boolean isRecruiter = session.getRecruiter().getEmail().equals(email);
        if (isRecruiter) return;

        if (isAssignedApplicant(session, email)) return;

        throw new AccessDeniedException(
                "Access denied: you are not assigned to this live interview session.");
    }

    /**
     * Returns true if the given email belongs to a user whose ID is in
     * the session's assignedApplicantIds set.
     */
    private boolean isAssignedApplicant(LiveInterviewSession session, String email) {
        return userRepo.findByEmail(email)
                .map(user -> session.getAssignedApplicantIds().contains(user.getId()))
                .orElse(false);
    }

    private void broadcastSessionStatus(LiveInterviewSession session, String message) {
        SessionStatusNotification note = new SessionStatusNotification();
        note.setLiveSessionId(session.getId());
        note.setSessionStatus(session.getSessionStatus());
        note.setMessage(message);
        note.setTimestamp(LocalDateTime.now());
        messaging.convertAndSend(TOPIC_SESSION + session.getId() + "/status", note);
    }

    private LiveSessionResponse toFullResponse(LiveInterviewSession s) {
        LiveSessionResponse r = new LiveSessionResponse();
        r.setLiveSessionId(s.getId());
        r.setSessionToken(s.getSessionToken());
        r.setRecruiterId(s.getRecruiter().getId());
        r.setRecruiterName(s.getRecruiter().getName());
        r.setCandidateId(s.getCandidate().getId());
        r.setCandidateName(s.getCandidate().getName());
        r.setSessionStatus(s.getSessionStatus());
        r.setCameraEnabled(s.isCameraEnabled());
        r.setRecruiterCameraEnabled(s.isRecruiterCameraEnabled());
        r.setCreatedAt(s.getCreatedAt());
        r.setInterviewStartTime(s.getInterviewStartTime());
        r.setInterviewEndTime(s.getInterviewEndTime());
        r.setRecruiterNotes(s.getRecruiterNotes());
        r.setAssignedApplicantIds(new HashSet<>(s.getAssignedApplicantIds()));
        if (s.getInterviewStartTime() != null && s.getInterviewEndTime() != null) {
            r.setDurationSeconds(ChronoUnit.SECONDS.between(
                    s.getInterviewStartTime(), s.getInterviewEndTime()));
        }
        r.setSessionOrigin(s.getSessionOrigin() != null ? s.getSessionOrigin().name() : null);
        r.setCurrentQuestionNumber(s.getCurrentQuestionNumber());
        r.setCurrentQuestionText(s.getCurrentQuestionText());
        r.setTotalQuestions(s.getTotalQuestions());
        if (s.getInterviewSession() != null) {
            r.setInterviewSessionId(s.getInterviewSession().getId());
            if (s.getInterviewSession().getStatus() != null) {
                r.setAiInterviewStatus(s.getInterviewSession().getStatus().name());
            }
        }
        return r;
    }

    private LiveSessionSummaryResponse toSummaryResponse(LiveInterviewSession s) {
        return toSummaryResponse(s, false);
    }

    /**
     * GLUE FIX: overload that optionally includes the raw sessionToken.
     * Needed so the recruiter's live-broadcast dashboard (active-broadcasts /
     * by-interview-session lookups) can obtain the token required by the
     * WebRTC/STOMP signaling messages — every other caller of this DTO
     * (e.g. getSessionById, candidate-side lookups) keeps the previous
     * behaviour of leaving the token out.
     */
    private LiveSessionSummaryResponse toSummaryResponse(LiveInterviewSession s, boolean includeToken) {
        LiveSessionSummaryResponse r = new LiveSessionSummaryResponse();
        r.setLiveSessionId(s.getId());
        if (includeToken) {
            r.setSessionToken(s.getSessionToken());
        }
        r.setRecruiterId(s.getRecruiter().getId());
        r.setRecruiterName(s.getRecruiter().getName());
        r.setCandidateId(s.getCandidate().getId());
        r.setCandidateName(s.getCandidate().getName());
        r.setSessionStatus(s.getSessionStatus());
        r.setCameraEnabled(s.isCameraEnabled());
        r.setRecruiterCameraEnabled(s.isRecruiterCameraEnabled());
        r.setCreatedAt(s.getCreatedAt());
        r.setInterviewStartTime(s.getInterviewStartTime());
        r.setInterviewEndTime(s.getInterviewEndTime());
        r.setAssignedApplicantIds(new HashSet<>(s.getAssignedApplicantIds()));
        if (s.getInterviewStartTime() != null && s.getInterviewEndTime() != null) {
            r.setDurationSeconds(ChronoUnit.SECONDS.between(
                    s.getInterviewStartTime(), s.getInterviewEndTime()));
        }
        r.setSessionOrigin(s.getSessionOrigin() != null ? s.getSessionOrigin().name() : null);
        r.setCurrentQuestionNumber(s.getCurrentQuestionNumber());
        r.setCurrentQuestionText(s.getCurrentQuestionText());
        r.setTotalQuestions(s.getTotalQuestions());
        if (s.getInterviewSession() != null) {
            r.setInterviewSessionId(s.getInterviewSession().getId());
            if (s.getInterviewSession().getStatus() != null) {
                r.setAiInterviewStatus(s.getInterviewSession().getStatus().name());
            }
            try {
                var application = s.getInterviewSession().getApplication();
                if (application != null && application.getJob() != null) {
                    r.setJobTitle(application.getJob().getTitle());
                    if (application.getJob().getCompany() != null) {
                        r.setCompanyName(application.getJob().getCompany().getName());
                    }
                }
            } catch (Exception e) {
                log.warn("Could not enrich summary response with job/company data: {}", e.getMessage());
            }
        }
        return r;
    }
}