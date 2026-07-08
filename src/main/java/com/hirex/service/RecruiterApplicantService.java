package com.hirex.service;

import com.hirex.dto.AtsBreakdownDto;
import com.hirex.dto.AtsResultDto;
import com.hirex.dto.ApplicantDetailDto;
import com.hirex.entity.*;
import com.hirex.repository.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Powers the modern ATS-style "Recruiter Applicants" module.
 *
 * Responsibilities:
 *  - Return applicants strictly scoped to a single job (jobId), never
 *    leaking applicants from other postings.
 *  - Display ATS score only if already calculated (do NOT auto-calculate on fetch)
 *  - Provide explicit analyzeAts() method for when recruiter clicks "Analyze ATS"
 *  - Provide a single generic status-update endpoint that supports the
 *    full ATS workflow: Applied → Under Review → Shortlisted → Rejected,
 *    plus Interview Scheduled (only reachable from Shortlisted).
 *
 * FIX: Removed automatic ATS calculation from toDetailDto(). ATS scores are now
 * calculated only via explicit analyzeAts() method call, not on every applicant fetch.
 */
@Service
public class RecruiterApplicantService {

    private final ApplicationRepository appRepo;
    private final JobRepository jobRepo;
    private final ResumeRepository resumeRepo;
    private final JobSeekerProfileRepository profileRepo;
    private final AtsService atsService;
    private final ConversationService conversationService;
    private final InterviewSessionRepository interviewSessionRepo;

    public RecruiterApplicantService(ApplicationRepository appRepo,
                                     JobRepository jobRepo,
                                     ResumeRepository resumeRepo,
                                     JobSeekerProfileRepository profileRepo,
                                     AtsService atsService,
                                     ConversationService conversationService,
                                     InterviewSessionRepository interviewSessionRepo) {
        this.appRepo = appRepo;
        this.jobRepo = jobRepo;
        this.resumeRepo = resumeRepo;
        this.profileRepo = profileRepo;
        this.atsService = atsService;
        this.conversationService = conversationService;
        this.interviewSessionRepo = interviewSessionRepo;
    }

    /**
     * GET /api/jobs/{jobId}/applicants
     * Returns applicants filtered strictly by jobId, scoped to the
     * requesting manager's own job posting.
     *
     * IMPORTANT: No longer auto-calculates ATS scores. Use analyzeAts() explicitly.
     */
    public List<ApplicantDetailDto> getApplicantsForJob(Long jobId, String managerEmail) {
        Job job = jobRepo.findByIdAndManagerEmail(jobId, managerEmail)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "Job not found or you do not have permission to view its applicants."));

        List<Application> applications = appRepo.findByJobId(jobId); // already filters WHERE j.id = :jobId

        return applications.stream()
                .map(app -> toDetailDto(app, job))
                .collect(Collectors.toList());
    }

    /**
     * GET single applicant detail (used by the candidate details page/modal).
     *
     * IMPORTANT: No longer auto-calculates ATS scores. Use analyzeAts() explicitly.
     */
    public ApplicantDetailDto getApplicantDetail(Long applicationId, String managerEmail) {
        Application app = appRepo.findById(applicationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Application not found"));
        assertOwnership(app, managerEmail);
        return toDetailDto(app, app.getJob());
    }

    /**
     * PATCH /api/applications/{applicationId}/status
     * Generic status transition used by Shortlist / Reject / Schedule Live
     * Interview / Under Review actions on the applicants table.
     */
    public ApplicantDetailDto updateStatus(Long applicationId, String statusValue, String managerEmail) {
        Application app = appRepo.findById(applicationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Application not found"));
        assertOwnership(app, managerEmail);

        ApplicationStatus newStatus;
        try {
            newStatus = ApplicationStatus.valueOf(statusValue.trim().toUpperCase());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid status: " + statusValue);
        }

        // Live interviews may only be scheduled for shortlisted candidates.
        if (newStatus == ApplicationStatus.INTERVIEW_SCHEDULED
                && app.getStatus() != ApplicationStatus.SHORTLISTED
                && app.getStatus() != ApplicationStatus.INTERVIEW_SCHEDULED) {
            throw new IllegalStateException("Only shortlisted candidates can have a live interview scheduled.");
        }

        app.setStatus(newStatus);
        if (newStatus == ApplicationStatus.SHORTLISTED) {
            app.setShortlistedAt(LocalDateTime.now());
            if (app.getAtsStatus() == null) {
                app.setAtsStatus("SHORTLISTED");
            }
        }
        if (newStatus == ApplicationStatus.REJECTED) {
            app.setAtsStatus("REJECTED");
        }

        Application saved = appRepo.save(app);

        // Requirement #2: manual shortlist also auto-creates the conversation
        // (idempotent — safe even if ATS already created it).
        if (saved.getStatus() == ApplicationStatus.SHORTLISTED) {
            conversationService.ensureConversationForShortlisted(saved);
        }

        return toDetailDto(saved, saved.getJob());
    }

    /**
     * POST /api/applications/{applicationId}/analyze-ats
     *
     * EXPLICIT ATS ANALYSIS - Called when recruiter clicks "Analyze ATS" button
     *
     * This is the ONLY place where ATS scores should be calculated and persisted.
     * - Retrieves the application and validates ownership
     * - Fetches the candidate's resume
     * - Calls AtsService to calculate score for this specific job
     * - Persists the score to database
     * - Updates the atsCheckedAt timestamp
     * - Returns the updated applicant detail with calculated score
     *
     * FIX: This method separates the concern of "analysis" from "fetching applicants"
     */
    public ApplicantDetailDto analyzeAts(Long applicationId, String managerEmail) {
        Application app = appRepo.findById(applicationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Application not found"));

        assertOwnership(app, managerEmail);

        User candidate = app.getApplicant();
        Job job = app.getJob();

        // Fetch resume text
        Optional<Resume> resumeOpt = resumeRepo.findByUserId(candidate.getId());
        String resumeText = resumeOpt.map(Resume::getResumeText).orElse("");

        // CALCULATE ATS SCORE (only at this point, not during fetch)
        AtsResultDto atsResult = atsService.checkForJob(resumeText, job);

        // PERSIST the score to database
        app.setAtsScore(atsResult.getAtsScore());

        // Derive the 3-tier status (80+ Shortlisted, 60-79 Under Review, <60 Rejected)
        // and only auto-advance the Application status if it's still at a
        // pre-decision stage — never downgrade a candidate who has already
        // progressed to interview/hired stages.
        String derivedAtsStatus = AtsService.deriveAtsStatus(atsResult.getAtsScore());
        app.setAtsStatus(derivedAtsStatus);
        if (AtsService.ATS_WRITABLE_STATUSES.contains(app.getStatus())) {
            app.setStatus(ApplicationStatus.valueOf(derivedAtsStatus));
            if (app.getStatus() == ApplicationStatus.SHORTLISTED) {
                app.setShortlistedAt(LocalDateTime.now());
            }
        }

        // Update the timestamp to show when ATS was analyzed
        app.setAtsCheckedAt(LocalDateTime.now());

        // Save to database
        Application saved = appRepo.save(app);

        // Requirement #2: auto-create the conversation the moment a candidate
        // becomes SHORTLISTED. Idempotent — never duplicates.
        if (saved.getStatus() == ApplicationStatus.SHORTLISTED) {
            conversationService.ensureConversationForShortlisted(saved);
        }

        // Return updated detail (which will include the calculated ATS score)
        return toDetailDto(saved, job);
    }

    /**
     * POST /api/applications/{applicationId}/hire
     *
     * MANUAL HIRING WORKFLOW
     *
     * Hiring is NEVER automatic. A recruiter must explicitly confirm on the
     * candidate's final evaluation page, after every prior stage — ATS
     * screening -> Recruiter Conversation -> AI Interview -> Interview
     * Evaluation — has completed successfully (application status is
     * INTERVIEW_PASSED).
     *
     * Guards:
     *  - Only the owning recruiter/manager (job owner) may hire.
     *  - Rejected candidates can never be hired.
     *  - Candidates whose interview is incomplete (not yet evaluated /
     *    passed) can never be hired.
     *  - Already-hired candidates cannot be hired again (idempotency guard —
     *    the frontend also disables/hides the button after success).
     */
    public ApplicantDetailDto hireCandidate(Long applicationId, String managerEmail) {
        Application app = appRepo.findById(applicationId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Application not found"));

        assertOwnership(app, managerEmail);

        if (app.getStatus() == ApplicationStatus.HIRED) {
            throw new IllegalStateException("This candidate has already been hired.");
        }
        if (app.getStatus() == ApplicationStatus.REJECTED) {
            throw new IllegalStateException("Rejected candidates cannot be hired.");
        }
        if (app.getStatus() != ApplicationStatus.INTERVIEW_PASSED) {
            throw new IllegalStateException(
                    "Candidate cannot be hired until ATS screening, recruiter conversation, " +
                            "AI interview, and interview evaluation have all completed successfully.");
        }

        User recruiter = app.getJob().getCompany().getManager();

        app.setStatus(ApplicationStatus.HIRED);
        app.setHiredAt(LocalDateTime.now());
        app.setHiredBy(managerEmail);
        app.setHiredByName(recruiter != null ? recruiter.getName() : null);

        Application saved = appRepo.save(app);
        return toDetailDto(saved, saved.getJob());
    }

    // ── Internal helpers ─────────────────────────────────────────────

    private void assertOwnership(Application app, String managerEmail) {
        String jobManagerEmail = app.getJob().getCompany().getManager().getEmail();
        if (!jobManagerEmail.equalsIgnoreCase(managerEmail)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You do not have permission to access this application.");
        }
    }

    /**
     * FIXED: This method no longer auto-calculates ATS scores.
     *
     * It only:
     * - Displays the stored atsScore (if it exists)
     * - Populates ATS breakdown only if score was already analyzed
     * - Shows "Not Analyzed" placeholder data if score is null
     */
    private ApplicantDetailDto toDetailDto(Application app, Job job) {
        User candidate = app.getApplicant();

        Optional<Resume> resumeOpt = resumeRepo.findByUserId(candidate.getId());
        Optional<JobSeekerProfile> profileOpt = profileRepo.findByUser(candidate);

        String resumeText = resumeOpt.map(Resume::getResumeText).orElse("");

        // FIX: REMOVED the automatic ATS calculation block that was here.
        // ATS score is now ONLY calculated when analyzeAts() is explicitly called.
        // We simply use whatever score is stored in app.getAtsScore()

        ApplicantDetailDto dto = new ApplicantDetailDto();
        dto.setApplicationId(app.getId());
        dto.setJobId(job.getId());
        dto.setJobTitle(job.getTitle());
        dto.setStatus(app.getStatus() != null ? app.getStatus().name() : ApplicationStatus.APPLIED.name());
        dto.setAppliedAt(app.getAppliedAt() != null ? app.getAppliedAt().toString() : "");
        dto.setCanScheduleInterview(
                app.getStatus() == ApplicationStatus.SHORTLISTED
                        || app.getStatus() == ApplicationStatus.INTERVIEW_SCHEDULED
        );

        // Manual hiring workflow: the Hire button/action is only valid once
        // every prior stage (ATS -> Recruiter Conversation -> AI Interview ->
        // Interview Evaluation) has completed successfully.
        dto.setCanHire(app.getStatus() == ApplicationStatus.INTERVIEW_PASSED);
        dto.setHiredAt(app.getHiredAt() != null ? app.getHiredAt().toString() : null);
        dto.setHiredBy(app.getHiredByName() != null ? app.getHiredByName() : app.getHiredBy());

        // FIX: this was previously never populated anywhere, so the frontend
        // had no way to link to /manager/interview/{sessionId}/report — the
        // report page existed but nothing in the UI could reach it. Populate
        // it whenever an AI interview session exists for this application,
        // regardless of whether it's finished yet (report page itself
        // already handles in-progress / not-yet-evaluated sessions).
        interviewSessionRepo.findByApplicationId(app.getId())
                .ifPresent(session -> dto.setAiInterviewSessionId(session.getId()));

        dto.setCandidateId(candidate.getId());
        dto.setCandidateName(candidate.getName());
        dto.setCandidateEmail(candidate.getEmail());
        dto.setCandidatePhone(candidate.getPhone());

        profileOpt.ifPresent(p -> {
            dto.setCandidateLocation(p.getLocation());
            dto.setCandidateBio(p.getBio());
        });

        resumeOpt.ifPresent(r -> {
            dto.setResumeId(r.getId());
            dto.setResumeFileName(r.getFileName());
            dto.setHasResume(true);
        });

        // Skills: prefer job-seeker profile skills, fall back to stored ATS match if analyzed
        List<String> skills;
        if (profileOpt.isPresent() && profileOpt.get().getSkills() != null && !profileOpt.get().getSkills().isBlank()) {
            skills = atsService.parseSkills(profileOpt.get().getSkills());
        } else {
            // Only show matched keywords if ATS was analyzed
            if (app.getAtsScore() != null) {
                // Need to recalculate just the keywords for display
                AtsResultDto atsResult = atsService.checkForJob(resumeText, job);
                skills = new ArrayList<>(atsResult.getMatchedKeywords() != null ? atsResult.getMatchedKeywords() : List.of());
            } else {
                skills = List.of();
            }
        }
        dto.setSkills(skills);

        dto.setEducation(profileOpt.map(JobSeekerProfile::getEducation).orElse(null));
        dto.setExperience(profileOpt.map(JobSeekerProfile::getExperience).orElse(null));
        dto.setProjects(extractProjects(resumeText));

        // FIX: Set ATS score (will be null if not analyzed)
        Integer score = app.getAtsScore();
        dto.setAtsScore(score); // Keep as nullable Integer

        // Determine color based on score
        String color;
        if (score == null) {
            color = "gray"; // Not analyzed
            dto.setAtsAnalysisStatus("NOT_ANALYZED");
        } else {
            int s = score;
            color = s >= 80 ? "green" : s >= 60 ? "yellow" : "red";
            dto.setAtsAnalysisStatus("ANALYZED");
        }
        dto.setAtsScoreColor(color);

        // FIX: Only populate ATS breakdown if score has been analyzed
        if (app.getAtsScore() != null) {
            // Score was analyzed, populate full breakdown
            AtsResultDto atsResult = atsService.checkForJob(resumeText, job);

            AtsBreakdownDto breakdown = new AtsBreakdownDto();
            breakdown.setMatchedSkills(atsResult.getMatchedKeywords());
            breakdown.setMissingSkills(atsResult.getMissingKeywords());

            int matchedCount = atsResult.getMatchedKeywords() != null ? atsResult.getMatchedKeywords().size() : 0;
            int missingCount = atsResult.getMissingKeywords() != null ? atsResult.getMissingKeywords().size() : 0;
            int totalKeywords = matchedCount + missingCount;
            breakdown.setKeywordMatchPercent(totalKeywords == 0 ? 0 : (int) Math.round((matchedCount / (double) totalKeywords) * 100));

            boolean expMatch = resumeText != null && !resumeText.isBlank()
                    && (resumeText.toLowerCase().contains("experience") || resumeText.toLowerCase().contains("work history"));
            breakdown.setExperienceMatch(expMatch);
            breakdown.setExperienceNote(job.getExperience() != null
                    ? "Required: " + job.getExperience()
                    : "No specific experience requirement listed for this job.");

            boolean eduMatch = job.getEducation() == null || job.getEducation().isBlank()
                    || (resumeText != null && resumeText.toLowerCase().contains(
                    job.getEducation().toLowerCase().split("[ ,]")[0]));
            breakdown.setEducationMatch(eduMatch);
            breakdown.setEducationNote(job.getEducation() != null
                    ? "Required: " + job.getEducation()
                    : "No specific education requirement listed for this job.");

            breakdown.setAiSuggestions(atsResult.getSuggestions());
            dto.setAtsBreakdown(breakdown);
        } else {
            // Score not analyzed yet, show placeholder breakdown
            AtsBreakdownDto emptyBreakdown = new AtsBreakdownDto();
            emptyBreakdown.setMatchedSkills(List.of());
            emptyBreakdown.setMissingSkills(List.of());
            emptyBreakdown.setKeywordMatchPercent(0);
            emptyBreakdown.setExperienceMatch(false);
            emptyBreakdown.setEducationMatch(false);
            emptyBreakdown.setAiSuggestions(List.of(
                    "📊 Click 'Analyze ATS' to see detailed keyword matching, experience, and education breakdown.",
                    "⏱️ ATS analysis takes a few seconds and provides actionable feedback on resume alignment."
            ));
            dto.setAtsBreakdown(emptyBreakdown);
        }

        return dto;
    }

    private String scoreColor(int score) {
        if (score >= 80) return "green";
        if (score >= 60) return "yellow";
        return "red";
    }

    private List<String> extractProjects(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) return List.of();
        List<String> projects = new ArrayList<>();
        String[] lines = resumeText.split("\\r?\\n");
        boolean inProjectsSection = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String lower = trimmed.toLowerCase();
            if (lower.matches("^(projects?|key projects?|academic projects?)\\s*:?$")) {
                inProjectsSection = true;
                continue;
            }
            if (inProjectsSection) {
                if (lower.matches("^(experience|education|skills|certifications?|achievements?)\\s*:?$")) {
                    break;
                }
                if (trimmed.length() > 3) {
                    projects.add(trimmed.replaceFirst("^[-•*]\\s*", ""));
                }
                if (projects.size() >= 8) break;
            }
        }
        return projects;
    }
}