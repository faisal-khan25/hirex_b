package com.hirex.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * InterviewViolation — NEW: AI Interview Monitoring (Proctoring)
 *
 * Persisted log entry for a single real-time proctoring violation detected
 * during a live interview session. Written by LiveInterviewService when a
 * candidate's browser reports a violation over /app/live/violation, and
 * exposed to the recruiter via GET /api/live-interview/{id}/violations and
 * live over /topic/violation/{liveSessionId}.
 *
 * Kept intentionally lightweight (one table, indexed by session) since a
 * single interview may generate many rows in quick succession (e.g. repeated
 * face-absence checks) — no relationships are eagerly fetched.
 */
@Entity
@Table(
        name = "interview_violations",
        indexes = {
                @Index(name = "idx_violation_live_session", columnList = "live_session_id"),
                @Index(name = "idx_violation_type",          columnList = "violation_type"),
                @Index(name = "idx_violation_created_at",    columnList = "created_at")
        }
)
public class InterviewViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "live_session_id", nullable = false)
    private Long liveSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "violation_type", nullable = false)
    private ViolationType violationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private ViolationSeverity severity;

    @Column(name = "message", length = 500)
    private String message;

    /**
     * Free-form JSON string with detection details (e.g. confidence score,
     * face count, decibel level). Optional — kept as plain text so this
     * table has no dependency on a JSON column type across DB vendors
     * (works identically on local Postgres/H2 and Render's managed DB).
     */
    @Column(name = "metadata", length = 1000)
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public InterviewViolation() {}

    public InterviewViolation(Long liveSessionId, ViolationType violationType,
                              ViolationSeverity severity, String message, String metadata) {
        this.liveSessionId = liveSessionId;
        this.violationType = violationType;
        this.severity      = severity;
        this.message       = message;
        this.metadata      = metadata;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getLiveSessionId() { return liveSessionId; }
    public void setLiveSessionId(Long liveSessionId) { this.liveSessionId = liveSessionId; }

    public ViolationType getViolationType() { return violationType; }
    public void setViolationType(ViolationType violationType) { this.violationType = violationType; }

    public ViolationSeverity getSeverity() { return severity; }
    public void setSeverity(ViolationSeverity severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
