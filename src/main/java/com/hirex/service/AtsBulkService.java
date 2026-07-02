package com.hirex.service;

import com.hirex.dto.AtsBulkResponseDto;
import com.hirex.dto.AtsBulkResultDto;
import com.hirex.dto.AtsResultDto;
import com.hirex.dto.AtsSummaryDto;
import com.hirex.entity.*;
import com.hirex.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AtsBulkService
 *
 * FIX: processBatches() previously updated ALL applications for a user
 * (findByApplicantId) regardless of which job the resume was being scored
 * against. A candidate who applied to Job A and Job B would have BOTH
 * applications updated when only Job A's recruiter ran the ATS — leaking
 * status writes into another recruiter's data.
 *
 * Now the job-scoped and manager-scoped paths pass in the application
 * list explicitly so only the correct applications are updated.
 */
@Service
public class AtsBulkService {

    private static final int BATCH_SIZE = 50;

    private final ResumeRepository       resumeRepo;
    private final ApplicationRepository  appRepo;
    private final UserRepository         userRepo;
    private final AtsService             atsService;
    private final ConversationService    conversationService;

    public AtsBulkService(ResumeRepository resumeRepo,
                          ApplicationRepository appRepo,
                          UserRepository userRepo,
                          AtsService atsService,
                          ConversationService conversationService) {
        this.resumeRepo  = resumeRepo;
        this.appRepo     = appRepo;
        this.userRepo    = userRepo;
        this.atsService  = atsService;
        this.conversationService = conversationService;
    }

    // ─── Global (legacy — all recruiters) ───────────────────────────────

    public AtsBulkResponseDto analyzeAll() {
        List<Resume> allResumes = resumeRepo.findAllWithUser();
        return processBatches(allResumes, null, false);
    }

    @Transactional
    public AtsBulkResponseDto processAll() {
        List<Resume> allResumes = resumeRepo.findAllWithUser();
        return processBatches(allResumes, null, true);
    }

    // ─── Manager-scoped (only this recruiter's applicants) ───────────────

    public AtsBulkResponseDto analyzeAllForManager(String managerEmail) {
        List<Application> apps = appRepo.findByManagerEmail(managerEmail);
        List<Resume> resumes = resumesForApplications(apps);
        return processBatches(resumes, apps, false);
    }

    @Transactional
    public AtsBulkResponseDto processAllForManager(String managerEmail) {
        List<Application> apps = appRepo.findByManagerEmail(managerEmail);
        List<Resume> resumes = resumesForApplications(apps);
        return processBatches(resumes, apps, true);
    }

    // ─── Per-job scoped (single job, ownership verified) ─────────────────

    /**
     * Score (preview only) resumes for applicants of a single job.
     * Throws if the job doesn't belong to the requesting manager.
     */
    public AtsBulkResponseDto analyzeForJob(Long jobId, String managerEmail) {
        List<Application> apps = getJobApplicationsForManager(jobId, managerEmail);
        List<Resume> resumes = resumesForApplications(apps);
        return processBatches(resumes, apps, false);
    }

    /**
     * Score and persist statuses for applicants of a single job.
     * Throws if the job doesn't belong to the requesting manager.
     */
    @Transactional
    public AtsBulkResponseDto processForJob(Long jobId, String managerEmail) {
        List<Application> apps = getJobApplicationsForManager(jobId, managerEmail);
        List<Resume> resumes = resumesForApplications(apps);
        return processBatches(resumes, apps, true);
    }

    /**
     * Load applications for a specific job, asserting it belongs to this manager.
     * Uses findByJobId which already eager-loads applicant + job.
     */
    private List<Application> getJobApplicationsForManager(Long jobId, String managerEmail) {
        List<Application> apps = appRepo.findByJobId(jobId);
        // Verify ownership: all apps for this job must belong to this manager
        boolean owned = apps.isEmpty() || apps.get(0).getJob()
                .getCompany().getManager().getEmail().equals(managerEmail);
        if (!owned) {
            throw new RuntimeException(
                    "Job " + jobId + " does not belong to manager " + managerEmail);
        }
        return apps;
    }

    // ─── Summary ─────────────────────────────────────────────────────────

    public AtsSummaryDto getSummary() {
        long total       = appRepo.count();
        long hired       = appRepo.countByStatus(ApplicationStatus.HIRED);
        long shortlisted = appRepo.countByStatus(ApplicationStatus.SHORTLISTED);
        long underReview = appRepo.countByStatus(ApplicationStatus.UNDER_REVIEW);
        long rejected    = appRepo.countByStatus(ApplicationStatus.REJECTED);
        long pending     = appRepo.countByStatus(ApplicationStatus.APPLIED);
        long withResume  = resumeRepo.count();
        return new AtsSummaryDto(total, hired, shortlisted, underReview, rejected, pending, withResume);
    }

    public AtsSummaryDto getSummaryForManager(String managerEmail) {
        long total       = appRepo.countByManagerEmail(managerEmail);
        long hired       = appRepo.countByManagerEmailAndStatus(managerEmail, ApplicationStatus.HIRED);
        long shortlisted = appRepo.countByManagerEmailAndStatus(managerEmail, ApplicationStatus.SHORTLISTED);
        long underReview = appRepo.countByManagerEmailAndStatus(managerEmail, ApplicationStatus.UNDER_REVIEW);
        long rejected    = appRepo.countByManagerEmailAndStatus(managerEmail, ApplicationStatus.REJECTED);
        long pending     = appRepo.countByManagerEmailAndStatus(managerEmail, ApplicationStatus.APPLIED);
        long withResume  = appRepo.countApplicantsWithResumeByManager(managerEmail);
        return new AtsSummaryDto(total, hired, shortlisted, underReview, rejected, pending, withResume);
    }

    // ─── Core batch processor ─────────────────────────────────────────────

    /**
     * @param allResumes     Resumes to score.
     * @param scopedApps     FIX: The pre-loaded applications to update on persist.
     *                       When null (global path), falls back to the old
     *                       findByApplicantId behaviour. When provided, only
     *                       those specific applications are updated — preventing
     *                       cross-recruiter status leaks.
     * @param persistStatus  Whether to write derived status to the DB.
     */
    @Transactional
    protected AtsBulkResponseDto processBatches(List<Resume> allResumes,
                                                List<Application> scopedApps,
                                                boolean persistStatus) {

        // ── BUG FIX ──────────────────────────────────────────────────────
        // Previously this method always scored every resume with
        // atsService.check(text) — a GENERIC scorer that matches against a
        // hardcoded skill list ("Java, Spring Boot, REST API, MySQL...")
        // that has nothing to do with the job the candidate applied for.
        // That's why the "ATS Analysis" page showed a different score
        // (e.g. 65) than the per-job Applicants table (e.g. 28), which
        // correctly scores via atsService.checkForJob(text, job).
        //
        // Whenever we have job-scoped applications (scopedApps != null —
        // i.e. analyzeForJob/processForJob or the manager-scoped paths),
        // we now score EACH APPLICATION against its OWN job's real
        // requirements, exactly like the Applicants table does. A single
        // resume can score differently for different jobs, so we iterate
        // applications (not deduplicated resumes) in that case.
        //
        // The legacy global (admin, no job context) path still falls back
        // to the generic scorer, since there's no single job to score against.
        if (scopedApps != null) {
            return processScopedApplications(scopedApps, persistStatus);
        }

        // Build a lookup: userId → their scoped applications (for fast access)
        Map<Long, List<Application>> appsByUserId = null;

        List<AtsBulkResultDto> results = new ArrayList<>();
        int totalProcessed = 0;
        int totalSkipped   = 0;
        int shortlistCount = 0;
        int underReviewCount = 0;
        int rejectedCount  = 0;

        int total = allResumes.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<Resume> batch = allResumes.subList(i, Math.min(i + BATCH_SIZE, total));

            for (Resume resume : batch) {
                AtsBulkResultDto row = new AtsBulkResultDto();
                row.setResumeId(resume.getId());
                row.setUserId(resume.getUser().getId());
                row.setCandidateName(resume.getUser().getName());
                row.setCandidateEmail(resume.getUser().getEmail());
                row.setFileName(resume.getFileName());

                String text = resume.getResumeText();
                if (text == null || text.isBlank()) {
                    row.setAtsScore(0);
                    row.setMatchPercentage(0);
                    row.setStatus("REJECTED");
                    row.setProcessed(false);
                    totalSkipped++;
                } else {
                    AtsResultDto atsResult = atsService.check(text);
                    int score = atsResult.getAtsScore();
                    String derivedStatus = deriveStatus(score);

                    row.setAtsScore(score);
                    row.setMatchPercentage(score);
                    row.setStatus(derivedStatus);
                    row.setProcessed(true);
                    totalProcessed++;

                    if ("SHORTLISTED".equals(derivedStatus)) shortlistCount++;
                    else if ("UNDER_REVIEW".equals(derivedStatus)) underReviewCount++;
                    else rejectedCount++;

                    if (persistStatus) {
                        // global (legacy) path — all applications for this user
                        List<Application> appsToUpdate = appRepo.findByApplicantId(resume.getUser().getId());

                        if (!appsToUpdate.isEmpty()) {
                            ApplicationStatus statusEnum = ApplicationStatus.valueOf(derivedStatus);
                            for (Application app : appsToUpdate) {
                                // Never downgrade a candidate who has already moved
                                // past the ATS-controlled stages.
                                if (!AtsService.ATS_WRITABLE_STATUSES.contains(app.getStatus())) continue;
                                app.setStatus(statusEnum);
                                app.setAtsScore(score);
                            }
                            appRepo.saveAll(appsToUpdate);
                            row.setApplicationId(appsToUpdate.get(0).getId());

                            if (statusEnum == ApplicationStatus.SHORTLISTED) {
                                for (Application app : appsToUpdate) {
                                    if (app.getStatus() == ApplicationStatus.SHORTLISTED) {
                                        conversationService.ensureConversationForShortlisted(app);
                                    }
                                }
                            }
                        }
                    }
                }
                results.add(row);
            }
        }

        AtsBulkResponseDto response = new AtsBulkResponseDto();
        response.setTotalProcessed(totalProcessed);
        response.setTotalSkipped(totalSkipped);
        response.setTotalHired(0); // ATS never assigns HIRED; kept for DTO compatibility
        response.setTotalShortlisted(shortlistCount);
        response.setTotalUnderReview(underReviewCount);
        response.setTotalRejected(rejectedCount);
        response.setResults(results);
        response.setMessage(buildMessage(totalProcessed, totalSkipped, persistStatus));
        return response;
    }

    /**
     * BUG FIX: scores each application against ITS OWN job's actual
     * requirements (atsService.checkForJob), matching exactly what the
     * Applicants table shows. This replaces the old behaviour of scoring
     * once per resume against a generic, job-agnostic skill list.
     *
     * One row is produced per APPLICATION (not per resume) — the same
     * resume can legitimately score differently against different jobs.
     */
    private AtsBulkResponseDto processScopedApplications(List<Application> scopedApps, boolean persistStatus) {
        List<AtsBulkResultDto> results = new ArrayList<>();
        int totalProcessed = 0;
        int totalSkipped   = 0;
        int shortlistCount = 0;
        int underReviewCount = 0;
        int rejectedCount  = 0;

        // Pre-fetch resumes for all applicants in one query to avoid N+1 lookups.
        List<Long> userIds = scopedApps.stream()
                .map(a -> a.getApplicant().getId())
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Resume> resumeByUserId = resumeRepo.findAllWithUser().stream()
                .filter(r -> userIds.contains(r.getUser().getId()))
                .collect(Collectors.toMap(r -> r.getUser().getId(), r -> r, (a, b) -> a));

        List<Application> appsToSave = new ArrayList<>();
        List<Application> newlyShortlisted = new ArrayList<>();

        for (Application app : scopedApps) {
            Resume resume = resumeByUserId.get(app.getApplicant().getId());

            AtsBulkResultDto row = new AtsBulkResultDto();
            row.setApplicationId(app.getId());
            row.setUserId(app.getApplicant().getId());
            row.setCandidateName(app.getApplicant().getName());
            row.setCandidateEmail(app.getApplicant().getEmail());

            if (resume == null) {
                row.setAtsScore(0);
                row.setMatchPercentage(0);
                row.setStatus("REJECTED");
                row.setProcessed(false);
                totalSkipped++;
                results.add(row);
                continue;
            }
            row.setResumeId(resume.getId());
            row.setFileName(resume.getFileName());

            String text = resume.getResumeText();
            if (text == null || text.isBlank()) {
                row.setAtsScore(0);
                row.setMatchPercentage(0);
                row.setStatus("REJECTED");
                row.setProcessed(false);
                totalSkipped++;
            } else {
                // ★ Score against THIS application's actual job — same logic
                // the Applicants table uses, so the two pages always agree.
                AtsResultDto atsResult = atsService.checkForJob(text, app.getJob());
                int score = atsResult.getAtsScore();
                // 80–100 SHORTLISTED, 60–79 UNDER_REVIEW, <60 REJECTED
                String derivedStatus = deriveStatus(score);

                row.setAtsScore(score);
                row.setMatchPercentage(score);
                row.setStatus(derivedStatus);
                row.setProcessed(true);
                totalProcessed++;

                if ("SHORTLISTED".equals(derivedStatus)) shortlistCount++;
                else if ("UNDER_REVIEW".equals(derivedStatus)) underReviewCount++;
                else rejectedCount++;

                // Never downgrade a candidate who has already moved past the
                // ATS-controlled stages (interview scheduled/completed, hired, etc.)
                boolean statusIsWritable = AtsService.ATS_WRITABLE_STATUSES.contains(app.getStatus());

                if (persistStatus) {
                    app.setAtsScore(score);
                    app.setAtsStatus(derivedStatus);
                    if (statusIsWritable) {
                        app.setStatus(ApplicationStatus.valueOf(derivedStatus));
                        if (app.getStatus() == ApplicationStatus.SHORTLISTED) {
                            newlyShortlisted.add(app);
                        }
                    }
                    appsToSave.add(app);
                }
            }
            results.add(row);
        }

        if (!appsToSave.isEmpty()) {
            appRepo.saveAll(appsToSave);
        }

        // Requirement #2: auto-create the conversation the moment a candidate
        // becomes SHORTLISTED. Idempotent — never creates duplicates.
        for (Application app : newlyShortlisted) {
            conversationService.ensureConversationForShortlisted(app);
        }

        AtsBulkResponseDto response = new AtsBulkResponseDto();
        response.setTotalProcessed(totalProcessed);
        response.setTotalSkipped(totalSkipped);
        response.setTotalHired(0);
        response.setTotalShortlisted(shortlistCount);
        response.setTotalUnderReview(underReviewCount);
        response.setTotalRejected(rejectedCount);
        response.setResults(results);
        response.setMessage(buildMessage(totalProcessed, totalSkipped, persistStatus));
        return response;
    }

    /** 80–100 → SHORTLISTED, 60–79 → UNDER_REVIEW, &lt;60 → REJECTED. ATS never sets HIRED. */
    public static String deriveStatus(int score) {
        return AtsService.deriveAtsStatus(score);
    }

    private String buildMessage(int processed, int skipped, boolean persisted) {
        String base = String.format("Analysed %d resume%s.", processed, processed == 1 ? "" : "s");
        if (skipped > 0) base += String.format(" %d skipped (no text extracted).", skipped);
        if (persisted)   base += " Statuses saved to database.";
        return base;
    }

    private List<Resume> resumesForApplications(List<Application> apps) {
        List<Long> userIds = apps.stream()
                .map(a -> a.getApplicant().getId())
                .distinct()
                .collect(Collectors.toList());
        return resumeRepo.findAllWithUser().stream()
                .filter(r -> userIds.contains(r.getUser().getId()))
                .collect(Collectors.toList());
    }
}