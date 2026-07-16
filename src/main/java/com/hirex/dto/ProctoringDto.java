package com.hirex.dto;

import com.hirex.entity.ViolationSeverity;
import com.hirex.entity.ViolationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * ProctoringDto — NEW: AI Interview Monitoring
 *
 * All request/response/WebSocket payloads for the real-time AI proctoring
 * feature: phone detection, face detection, multiple-face detection,
 * noise detection, face-absence detection, tab-switch detection, and
 * camera/microphone status monitoring.
 *
 * Detection itself runs client-side (candidate's browser) for real-time
 * performance — the backend's job is purely to authorize, persist, and
 * fan the violation out to the recruiter over WebSocket.
 */
public class ProctoringDto {

    // =========================================================================
    // 1.  Candidate → Server  (STOMP /app/live/violation)
    // =========================================================================

    public static class ViolationReportMessage {
        @NotBlank(message = "sessionToken is required")
        private String sessionToken;

        @NotNull(message = "violationType is required")
        private ViolationType violationType;

        /**
         * Optional — if omitted, the server derives a default severity per
         * violationType (see LiveInterviewService#defaultSeverityFor). The
         * client MAY escalate (e.g. NO_FACE_DETECTED -> FACE_ABSENCE_PROLONGED
         * after N consecutive seconds) but may not downgrade below CRITICAL
         * for PHONE_DETECTED; the server clamps severity server-side as a
         * defense-in-depth measure.
         */
        private ViolationSeverity severity;

        private String message;

        /** Optional free-form detail, e.g. `{"confidence":0.87,"faceCount":2}` */
        private String metadata;

        public String getSessionToken() { return sessionToken; }
        public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }

        public ViolationType getViolationType() { return violationType; }
        public void setViolationType(ViolationType violationType) { this.violationType = violationType; }

        public ViolationSeverity getSeverity() { return severity; }
        public void setSeverity(ViolationSeverity severity) { this.severity = severity; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getMetadata() { return metadata; }
        public void setMetadata(String metadata) { this.metadata = metadata; }
    }

    // =========================================================================
    // 2.  Server → Both parties  (broadcast /topic/violation/{liveSessionId})
    // =========================================================================

    /**
     * Broadcast to BOTH the candidate (so their own live-warning banner can
     * confirm the alert was recorded) and the recruiter (so the broadcast
     * panel's live violation feed updates instantly).
     */
    public static class ViolationNotification {
        private Long id;
        private Long liveSessionId;
        private ViolationType violationType;
        private ViolationSeverity severity;
        private String message;
        private LocalDateTime timestamp;

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

        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    }

    // =========================================================================
    // 3.  REST — full violation log  (GET /api/live-interview/{id}/violations)
    // =========================================================================

    public static class ViolationLogEntry {
        private Long id;
        private ViolationType violationType;
        private ViolationSeverity severity;
        private String message;
        private String metadata;
        private LocalDateTime timestamp;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public ViolationType getViolationType() { return violationType; }
        public void setViolationType(ViolationType violationType) { this.violationType = violationType; }

        public ViolationSeverity getSeverity() { return severity; }
        public void setSeverity(ViolationSeverity severity) { this.severity = severity; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getMetadata() { return metadata; }
        public void setMetadata(String metadata) { this.metadata = metadata; }

        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    }

    public static class ViolationLogResponse {
        private Long liveSessionId;
        private long totalCount;
        private long criticalCount;
        private long highCount;
        private long mediumCount;
        private long lowCount;
        private java.util.List<ViolationLogEntry> entries;

        public Long getLiveSessionId() { return liveSessionId; }
        public void setLiveSessionId(Long liveSessionId) { this.liveSessionId = liveSessionId; }

        public long getTotalCount() { return totalCount; }
        public void setTotalCount(long totalCount) { this.totalCount = totalCount; }

        public long getCriticalCount() { return criticalCount; }
        public void setCriticalCount(long criticalCount) { this.criticalCount = criticalCount; }

        public long getHighCount() { return highCount; }
        public void setHighCount(long highCount) { this.highCount = highCount; }

        public long getMediumCount() { return mediumCount; }
        public void setMediumCount(long mediumCount) { this.mediumCount = mediumCount; }

        public long getLowCount() { return lowCount; }
        public void setLowCount(long lowCount) { this.lowCount = lowCount; }

        public java.util.List<ViolationLogEntry> getEntries() { return entries; }
        public void setEntries(java.util.List<ViolationLogEntry> entries) { this.entries = entries; }
    }
}
