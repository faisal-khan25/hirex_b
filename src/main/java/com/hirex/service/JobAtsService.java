package com.hirex.service;

import com.hirex.dto.AtsResultDto;
import com.hirex.dto.JobAtsResponseDto;
import com.hirex.entity.*;
import com.hirex.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JobAtsService
 *
 * Performs job-specific ATS shortlisting:
 *   1. Fetch the job and its required skills/experience/education.
 *   2. Score ONLY the resumes of candidates who applied to THAT job.
 *   3. Persist atsScore + atsStatus (SHORTLISTED / REJECTED) on each Application.
 *   4. Return a per-job summary with per-candidate results.
 *
 * Threshold: AtsService.ATS_THRESHOLD (default 80)
 */
@Service
public class JobAtsService {

    private static final Logger log = LoggerFactory.getLogger(JobAtsService.class);

    private final ApplicationRepository appRepo;
    private final JobRepository         jobRepo;
    private final ResumeRepository      resumeRepo;
    private final AtsService            atsService;
    private final ConversationService   conversationService;


    public JobAtsService(ApplicationRepository appRepo,
                         JobRepository jobRepo,
                         ResumeRepository resumeRepo,
                         AtsService atsService,
                         ConversationService conversationService) {
        this.appRepo    = appRepo;
        this.jobRepo    = jobRepo;
        this.resumeRepo = resumeRepo;
        this.atsService = atsService;
        this.conversationService = conversationService;
    }

    // ── Run ATS for a job (score + persist) ──────────────────────────────

    /**
     * Runs job-specific ATS for every applicant of {@code jobId}.
     * @param managerEmail the authenticated recruiter — ownership is verified
     *                      so a recruiter can never trigger ATS on a job they
     *                      did not post.
     */
    @Transactional
    public JobAtsResponseDto runAtsForJob(Long jobId, String managerEmail) {
        Job job = jobRepo.findByIdAndManagerEmail(jobId, managerEmail)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
                        "Job not found or you do not have permission to process its applicants."));

        List<Application> applications = appRepo.findByJobId(jobId);

        if (applications.isEmpty()) {
            return JobAtsResponseDto.empty(job.getTitle(), jobId);
        }

        // Build a userId → Resume map for fast lookup
        Map<Long, Resume> resumeMap = buildResumeMap(applications);

        List<JobAtsResponseDto.CandidateAtsResult> results = new ArrayList<>();
        int shortlisted  = 0;
        int underReview  = 0;
        int rejected     = 0;
        int skipped      = 0;

        for (Application app : applications) {
            Long userId = app.getApplicant().getId();
            Resume resume = resumeMap.get(userId);

            boolean statusIsWritable = AtsService.ATS_WRITABLE_STATUSES.contains(app.getStatus());

            if (resume == null || resume.getResumeText() == null || resume.getResumeText().isBlank()) {
                // No resume — mark rejected
                app.setAtsScore(0);
                app.setAtsStatus("REJECTED");
                app.setAtsCheckedAt(LocalDateTime.now());
                app.setShortlistReason("No resume uploaded.");
                if (statusIsWritable) app.setStatus(ApplicationStatus.REJECTED);
                appRepo.save(app);
                skipped++;

                results.add(JobAtsResponseDto.CandidateAtsResult.builder()
                        .applicationId(app.getId())
                        .candidateName(app.getApplicant().getName())
                        .candidateEmail(app.getApplicant().getEmail())
                        .atsScore(0)
                        .atsStatus("REJECTED")
                        .matchedSkills(List.of())
                        .missingSkills(List.of())
                        .note("No resume found")
                        .build());
                continue;
            }

            // Score against THIS job's requirements
            AtsResultDto score = atsService.checkForJob(resume.getResumeText(), job);

            // 80–100 SHORTLISTED, 60–79 UNDER_REVIEW, <60 REJECTED
            String status = AtsService.deriveAtsStatus(score.getAtsScore());

            // Persist
            app.setAtsScore(score.getAtsScore());
            app.setAtsStatus(status);
            app.setAtsCheckedAt(LocalDateTime.now());
            app.setShortlistReason(buildReason(score, job));
            app.setShortlistSource("ATS");

            // Never downgrade a candidate who has already moved past the
            // ATS-controlled stages (interview scheduled/completed, hired, etc.)
            if (statusIsWritable) {
                switch (status) {
                    case "SHORTLISTED":
                        app.setShortlistedAt(LocalDateTime.now());
                        app.setStatus(ApplicationStatus.SHORTLISTED);
                        break;
                    case "UNDER_REVIEW":
                        app.setStatus(ApplicationStatus.UNDER_REVIEW);
                        break;
                    default: // REJECTED
                        app.setStatus(ApplicationStatus.REJECTED);
                }
            }

            if ("SHORTLISTED".equals(status)) shortlisted++;
            else if ("UNDER_REVIEW".equals(status)) underReview++;
            else rejected++;

            Application saved = appRepo.save(app);

            // Requirement #2: auto-create the conversation the moment a
            // candidate becomes SHORTLISTED. Idempotent — never duplicates.
            if (saved.getStatus() == ApplicationStatus.SHORTLISTED) {
                conversationService.ensureConversationForShortlisted(saved);
            }

            results.add(JobAtsResponseDto.CandidateAtsResult.builder()
                    .applicationId(app.getId())
                    .candidateName(app.getApplicant().getName())
                    .candidateEmail(app.getApplicant().getEmail())
                    .atsScore(score.getAtsScore())
                    .atsStatus(status)
                    .matchedSkills(score.getMatchedKeywords())
                    .missingSkills(score.getMissingKeywords())
                    .note(score.getShortlistMessage())
                    .build());
        }

        // Sort: shortlisted first, then under review, then by score desc
        results.sort(Comparator
                .comparing((JobAtsResponseDto.CandidateAtsResult r) -> {
                    if ("SHORTLISTED".equals(r.getAtsStatus())) return 0;
                    if ("UNDER_REVIEW".equals(r.getAtsStatus())) return 1;
                    return 2;
                })
                .thenComparing(Comparator.comparingInt(JobAtsResponseDto.CandidateAtsResult::getAtsScore).reversed()));

        return JobAtsResponseDto.builder()
                .jobId(jobId)
                .jobTitle(job.getTitle())
                .threshold(AtsService.ATS_THRESHOLD)
                .totalProcessed(applications.size())
                .shortlisted(shortlisted)
                .underReview(underReview)
                .rejected(rejected)
                .skipped(skipped)
                .candidates(results)
                .message(String.format("ATS complete for '%s'. Shortlisted: %d | Under Review: %d | Rejected: %d | Skipped: %d",
                        job.getTitle(), shortlisted, underReview, rejected, skipped))
                .build();
    }

    // ── Get shortlisted candidates for a job ─────────────────────────────

    public JobAtsResponseDto getShortlisted(Long jobId, String managerEmail) {
        Job job = jobRepo.findByIdAndManagerEmail(jobId, managerEmail)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
                        "Job not found or you do not have permission to view its applicants."));

        List<Application> apps = appRepo.findShortlistedByJobId(jobId);

        List<JobAtsResponseDto.CandidateAtsResult> results = apps.stream()
                .map(app -> JobAtsResponseDto.CandidateAtsResult.builder()
                        .applicationId(app.getId())
                        .candidateName(app.getApplicant().getName())
                        .candidateEmail(app.getApplicant().getEmail())
                        .atsScore(app.getAtsScore() != null ? app.getAtsScore() : 0)
                        .atsStatus(app.getAtsStatus())
                        .matchedSkills(List.of())
                        .missingSkills(List.of())
                        .build())
                .collect(Collectors.toList());

        long shortlisted = appRepo.countShortlistedByJobId(jobId);
        long rejected    = appRepo.countRejectedByJobId(jobId);

        return JobAtsResponseDto.builder()
                .jobId(jobId)
                .jobTitle(job.getTitle())
                .threshold(AtsService.ATS_THRESHOLD)
                .totalProcessed((int)(shortlisted + rejected))
                .shortlisted((int) shortlisted)
                .rejected((int) rejected)
                .skipped(0)
                .candidates(results)
                .message("Shortlisted candidates for job: " + job.getTitle())
                .build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<Long, Resume> buildResumeMap(List<Application> applications) {
        Set<Long> userIds = applications.stream()
                .map(a -> a.getApplicant().getId())
                .collect(Collectors.toSet());

        Map<Long, Resume> map = new HashMap<>();
        // Find most-recent resume per user
        resumeRepo.findAllWithUser().stream()
                .filter(r -> userIds.contains(r.getUser().getId()))
                .forEach(r -> map.merge(r.getUser().getId(), r,
                        (a, b) -> a.getUploadedAt().isAfter(b.getUploadedAt()) ? a : b));
        return map;
    }

    private String buildReason(AtsResultDto score, Job job) {
        int matched = score.getMatchedKeywords() != null ? score.getMatchedKeywords().size() : 0;
        int total   = matched + (score.getMissingKeywords() != null ? score.getMissingKeywords().size() : 0);
        String kws  = score.getMatchedKeywords() != null ? String.join(", ", score.getMatchedKeywords()) : "";
        return String.format("ATS score %d/100 (threshold %d) for '%s'. Matched %d/%d skills: %s.",
                score.getAtsScore(), AtsService.ATS_THRESHOLD, job.getTitle(), matched, total, kws);
    }
}