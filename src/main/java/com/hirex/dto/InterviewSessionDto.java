package com.hirex.dto;



import java.time.LocalDateTime;
import java.util.List;

// ADDED: Interview Session DTO
public class InterviewSessionDto {
    private Long id;
    private Long applicationId;
    private String interviewType; // AI, HUMAN, HYBRID
    private String status; // PENDING, IN_PROGRESS, COMPLETED, CANCELLED
    private LocalDateTime scheduledAt;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String recordingUrl;
    private String interviewTemplate;
    private Integer maxDurationMinutes;
    private String candidateName;
    private String positionTitle;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // ADD inside InterviewSessionDto class

    private String interviewPassStatus;  // PASSED / UNDER_REVIEW / FAILED / PENDING
    private Double interviewScore;       // 0-100 overall rating

    // ── Manual hiring workflow (used by the recruiter-facing report page) ──
    private String applicationStatus;    // current ApplicationStatus name (e.g. INTERVIEW_PASSED, HIRED, REJECTED)
    private boolean canHire;             // true only when applicationStatus == INTERVIEW_PASSED
    private String hiredAt;              // ISO date-time string, null if not hired
    private String hiredBy;              // recruiter name/email who hired the candidate

    public String getApplicationStatus() { return applicationStatus; }
    public void setApplicationStatus(String applicationStatus) { this.applicationStatus = applicationStatus; }

    public boolean isCanHire() { return canHire; }
    public void setCanHire(boolean canHire) { this.canHire = canHire; }

    public String getHiredAt() { return hiredAt; }
    public void setHiredAt(String hiredAt) { this.hiredAt = hiredAt; }

    public String getHiredBy() { return hiredBy; }
    public void setHiredBy(String hiredBy) { this.hiredBy = hiredBy; }

    public String getInterviewPassStatus() { return interviewPassStatus; }
    public void setInterviewPassStatus(String interviewPassStatus) {
        this.interviewPassStatus = interviewPassStatus;
    }

    public Double getInterviewScore() { return interviewScore; }
    public void setInterviewScore(Double interviewScore) {
        this.interviewScore = interviewScore;
    }

    // ADDED: Nested DTOs
    private List<InterviewQuestionDto> questions;
    private InterviewEvaluationDto evaluation;

    // ADDED: Constructors
    public InterviewSessionDto() {}

    public InterviewSessionDto(Long id, Long applicationId, String interviewType, String status) {
        this.id = id;
        this.applicationId = applicationId;
        this.interviewType = interviewType;
        this.status = status;
    }

    // ADDED: Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long applicationId) { this.applicationId = applicationId; }

    public String getInterviewType() { return interviewType; }
    public void setInterviewType(String interviewType) { this.interviewType = interviewType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }

    public String getRecordingUrl() { return recordingUrl; }
    public void setRecordingUrl(String recordingUrl) { this.recordingUrl = recordingUrl; }

    public String getInterviewTemplate() { return interviewTemplate; }
    public void setInterviewTemplate(String interviewTemplate) { this.interviewTemplate = interviewTemplate; }

    public Integer getMaxDurationMinutes() { return maxDurationMinutes; }
    public void setMaxDurationMinutes(Integer maxDurationMinutes) { this.maxDurationMinutes = maxDurationMinutes; }

    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String candidateName) { this.candidateName = candidateName; }

    public String getPositionTitle() { return positionTitle; }
    public void setPositionTitle(String positionTitle) { this.positionTitle = positionTitle; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<InterviewQuestionDto> getQuestions() { return questions; }
    public void setQuestions(List<InterviewQuestionDto> questions) { this.questions = questions; }

    public InterviewEvaluationDto getEvaluation() { return evaluation; }
    public void setEvaluation(InterviewEvaluationDto evaluation) { this.evaluation = evaluation; }
}

// ADDED: Interview Evaluation DTO