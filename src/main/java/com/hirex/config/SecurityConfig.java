package com.hirex.config;

import com.hirex.security.JwtFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * SecurityConfig — UPDATED
 *
 * CHANGES:
 *  NEW: Added /api/live-interview/assignable-applicants/** (MANAGER only)
 *       for the recruiter applicant picker when creating a live session.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // ── Security headers (Lighthouse "Best Practices") ─────────
                // X-Content-Type-Options and X-Frame-Options are already added by
                // Spring Security's defaults; the ones below are NOT on by default
                // and need Render's proxy to forward X-Forwarded-Proto correctly
                // (see server.forward-headers-strategy in application.properties)
                // for HSTS to actually fire, since Spring only sends it on requests
                // it believes are secure.
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .preload(true)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(referrer -> referrer
                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .addHeaderWriter((request, response) -> {
                            // Camera/mic are used only by same-origin interview pages —
                            // explicitly deny them to any embedding/third-party context.
                            response.setHeader("Permissions-Policy",
                                    "camera=(self), microphone=(self), display-capture=(self), geolocation=()");
                            // API responses aren't rendered as HTML — this is a "deny everything"
                            // CSP as defense-in-depth against this origin ever being framed or
                            // having its JSON responses interpreted as active content. The actual
                            // page CSP (script/style/font sources) belongs on the frontend host
                            // that serves index.html, not here.
                            response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
                        })
                )
                .authorizeHttpRequests(auth -> auth
                        // ── Public ────────────────────────────────────────────
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/app/**").permitAll()
                        .requestMatchers("/topic/**").permitAll()
                        .requestMatchers("/api/jobs/browse").permitAll()

                        // ── Admin ─────────────────────────────────────────────
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // ── Manager / Recruiter ───────────────────────────────
                        .requestMatchers("/api/manager/**").hasRole("MANAGER")
                        .requestMatchers("/api/ats/**").hasRole("MANAGER")
                        .requestMatchers("/api/jobs/*/ats/**").hasRole("MANAGER")
                        .requestMatchers("/api/jobs/*/shortlisted").hasRole("MANAGER")
                        .requestMatchers(HttpMethod.GET, "/api/jobs/*/applicants/**").hasRole("MANAGER")
                        .requestMatchers(HttpMethod.PATCH, "/api/applications/*/status").hasRole("MANAGER")
                        // Manual hiring workflow: recruiter/manager and admin only
                        .requestMatchers(HttpMethod.POST, "/api/applications/*/hire").hasAnyRole("MANAGER", "ADMIN")
                        .requestMatchers("/api/interview/assign-all/**").hasRole("MANAGER")
                        .requestMatchers("/api/interview/assign/**").hasRole("MANAGER")
                        .requestMatchers("/api/chat/manager/**").hasRole("MANAGER")

                        // ── Live Interview ────────────────────────────────────
                        // NOTE: More-specific rules MUST come before generic patterns.
                        .requestMatchers(HttpMethod.POST, "/api/live-interview/create").hasRole("MANAGER")
                        .requestMatchers(HttpMethod.GET,  "/api/live-interview/join/**").hasAnyRole("MANAGER", "JOBSEEKER")
                        .requestMatchers(HttpMethod.POST, "/api/live-interview/*/end").hasRole("MANAGER")
                        .requestMatchers(HttpMethod.GET,  "/api/live-interview/\\d+").hasRole("MANAGER")
                        .requestMatchers(HttpMethod.GET,  "/api/live-interview/candidate/**").hasRole("JOBSEEKER")
                        // NEW: recruiter fetches assignable applicants before creating a session
                        .requestMatchers(HttpMethod.GET,  "/api/live-interview/assignable-applicants/**").hasRole("MANAGER")
                        // NEW: Live Broadcasting for AI Interview
                        .requestMatchers(HttpMethod.GET,  "/api/live-interview/active-broadcasts").hasRole("MANAGER")
                        .requestMatchers(HttpMethod.GET,  "/api/live-interview/by-interview-session/**").hasAnyRole("MANAGER", "JOBSEEKER")
                        // NEW: AI Interview Monitoring — violation log is recruiter-only
                        .requestMatchers(HttpMethod.GET,  "/api/live-interview/*/violations").hasRole("MANAGER")
                        // ice-servers is authenticated (both roles)
                        .requestMatchers(HttpMethod.GET,  "/api/live-interview/ice-servers").authenticated()
                        // Catch-all for live-interview
                        .requestMatchers("/api/live-interview/**").authenticated()

                        // ── Jobseeker / Candidate ─────────────────────────────
                        .requestMatchers("/api/jobseeker/**").hasRole("JOBSEEKER")
                        .requestMatchers("/api/chat/jobseeker/**").hasRole("JOBSEEKER")

                        // ── Shared authenticated ──────────────────────────────
                        .requestMatchers("/api/chat/files/**").authenticated()
                        // NEW: recruiter generates & sends the interview link in chat
                        .requestMatchers(HttpMethod.POST, "/api/chat/application/*/generate-interview-link").hasRole("MANAGER")
                        .requestMatchers("/api/chat/application/**").authenticated()
                        .requestMatchers("/api/chat/messages/**").authenticated()

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}