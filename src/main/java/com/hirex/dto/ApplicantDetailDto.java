package com.hirex.dto;

import java.util.List;

/**
 * Full ATS-style applicant record returned by:
 *   GET /api/jobs/{jobId}/applicants
 *
 * Used both for the applicants table (most fields) and for the
 * Candidate Details modal/page (all fields, including atsBreakdown).
 *
 * FIX: Changed atsScore from int to Integer to properly handle null values
 * indicating "Not Analyzed" state.
 */
public class ApplicantDetailDto {

    // Application
    private Long applicationId;
    private Long jobId;
    private String jobTitle;
    private String status;             // ApplicationStatus name
    private String appliedAt;          // ISO date-time string
    private boolean canScheduleInterview;

    // AI interview — set only once an AI interview session exists for this
    // application, so the frontend can link to /manager/interview/{id}/report.
    // null = no AI interview has been scheduled/started yet.
    private Long aiInterviewSessionId;

    // Candidate
    private Long candidateId;
    private String candidateName;
    private String candidateEmail;
    private String candidatePhone;
    private String candidateLocation;
    private String candidateBio;

    // Resume
    private Long resumeId;
    private String resumeFileName;
    private boolean hasResume;

    // Profile detail
    private List<String> skills;
    private String education;
    private String experience;
    private List<String> projects;

    // ATS
    private Integer atsScore;            // null = not analyzed, 0-100 = analyzed (FIXED from int to Integer)
    private String atsScoreColor;        // green | yellow | red | gray (for null)
    private AtsBreakdownDto atsBreakdown;
    private String atsAnalysisStatus;    // NEW: null | "ANALYZED" | "NOT_ANALYZED"

    public ApplicantDetailDto() {}

    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long applicationId) { this.applicationId = applicationId; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAppliedAt() { return appliedAt; }
    public void setAppliedAt(String appliedAt) { this.appliedAt = appliedAt; }

    public boolean isCanScheduleInterview() { return canScheduleInterview; }
    public void setCanScheduleInterview(boolean canScheduleInterview) { this.canScheduleInterview = canScheduleInterview; }

    public Long getAiInterviewSessionId() { return aiInterviewSessionId; }
    public void setAiInterviewSessionId(Long aiInterviewSessionId) { this.aiInterviewSessionId = aiInterviewSessionId; }


    public Long getCandidateId() { return candidateId; }
    public void setCandidateId(Long candidateId) { this.candidateId = candidateId; }

    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String candidateName) { this.candidateName = candidateName; }

    public String getCandidateEmail() { return candidateEmail; }
    public void setCandidateEmail(String candidateEmail) { this.candidateEmail = candidateEmail; }

    public String getCandidatePhone() { return candidatePhone; }
    public void setCandidatePhone(String candidatePhone) { this.candidatePhone = candidatePhone; }

    public String getCandidateLocation() { return candidateLocation; }
    public void setCandidateLocation(String candidateLocation) { this.candidateLocation = candidateLocation; }

    public String getCandidateBio() { return candidateBio; }
    public void setCandidateBio(String candidateBio) { this.candidateBio = candidateBio; }

    public Long getResumeId() { return resumeId; }
    public void setResumeId(Long resumeId) { this.resumeId = resumeId; }

    public String getResumeFileName() { return resumeFileName; }
    public void setResumeFileName(String resumeFileName) { this.resumeFileName = resumeFileName; }

    public boolean isHasResume() { return hasResume; }
    public void setHasResume(boolean hasResume) { this.hasResume = hasResume; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public String getEducation() { return education; }
    public void setEducation(String education) { this.education = education; }

    public String getExperience() { return experience; }
    public void setExperience(String experience) { this.experience = experience; }

    public List<String> getProjects() { return projects; }
    public void setProjects(List<String> projects) { this.projects = projects; }

    public Integer getAtsScore() { return atsScore; }
    public void setAtsScore(Integer atsScore) { this.atsScore = atsScore; }

    public String getAtsScoreColor() { return atsScoreColor; }
    public void setAtsScoreColor(String atsScoreColor) { this.atsScoreColor = atsScoreColor; }

    public AtsBreakdownDto getAtsBreakdown() { return atsBreakdown; }
    public void setAtsBreakdown(AtsBreakdownDto atsBreakdown) { this.atsBreakdown = atsBreakdown; }

    public String getAtsAnalysisStatus() { return atsAnalysisStatus; }
    public void setAtsAnalysisStatus(String atsAnalysisStatus) { this.atsAnalysisStatus = atsAnalysisStatus; }
}