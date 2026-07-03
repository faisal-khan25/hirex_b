package com.hirex.controller;

import com.hirex.dto.ApplicantDetailDto;
import com.hirex.service.RecruiterApplicantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * RecruiterApplicantController
 *
 * Powers the modern ATS-style Recruiter Applicants module.
 *
 *   GET   /api/jobs/{jobId}/applicants            — applicants for ONE job only
 *   GET   /api/jobs/{jobId}/applicants/{appId}     — single candidate detail
 *   PATCH /api/applications/{applicationId}/status — Shortlist / Reject / etc.
 *   POST  /api/applications/{applicationId}/analyze-ats — EXPLICIT ATS ANALYSIS (NEW)
 */
@RestController
@CrossOrigin
public class RecruiterApplicantController {

    private final RecruiterApplicantService service;

    public RecruiterApplicantController(RecruiterApplicantService service) {
        this.service = service;
    }

    /**
     * GET /api/jobs/{jobId}/applicants
     * Returns applicants filtered strictly by jobId — never applicants from
     * other job postings. Includes ATS score + full breakdown per applicant.
     *
     * FIX: No longer auto-calculates ATS scores. Returns null if not analyzed.
     */
    @GetMapping("/api/jobs/{jobId}/applicants")
    public ResponseEntity<List<ApplicantDetailDto>> getApplicants(@PathVariable Long jobId,
                                                                  Principal principal) {
        return ResponseEntity.ok(service.getApplicantsForJob(jobId, principal.getName()));
    }

    /**
     * GET /api/jobs/{jobId}/applicants/{applicationId}
     * Full candidate profile + ATS breakdown for the Candidate Details page.
     *
     * FIX: No longer auto-calculates ATS scores. Returns null if not analyzed.
     */
    @GetMapping("/api/jobs/{jobId}/applicants/{applicationId}")
    public ResponseEntity<ApplicantDetailDto> getApplicantDetail(@PathVariable Long jobId,
                                                                 @PathVariable Long applicationId,
                                                                 Principal principal) {
        return ResponseEntity.ok(service.getApplicantDetail(applicationId, principal.getName()));
    }

    /**
     * PATCH /api/applications/{applicationId}/status
     * Body: { "status": "SHORTLISTED" }
     * Supported statuses: APPLIED, UNDER_REVIEW, SHORTLISTED, REJECTED,
     * INTERVIEW_SCHEDULED (only reachable once SHORTLISTED).
     */
    @PatchMapping("/api/applications/{applicationId}/status")
    public ResponseEntity<ApplicantDetailDto> updateStatus(@PathVariable Long applicationId,
                                                           @RequestBody StatusUpdateRequest req,
                                                           Principal principal) {
        return ResponseEntity.ok(service.updateStatus(applicationId, req.getStatus(), principal.getName()));
    }

    /**
     * POST /api/applications/{applicationId}/analyze-ats
     *
     * EXPLICITLY TRIGGER ATS ANALYSIS
     *
     * This endpoint should be called when the recruiter clicks the "Analyze ATS" button.
     * - Calculates ATS score based on resume vs. job requirements
     * - Persists score to database
     * - Updates atsCheckedAt timestamp
     * - Returns updated applicant detail with score
     *
     * FIX: This is the new endpoint that properly separates "fetch applicants"
     * from "analyze ATS score".
     */
    @PostMapping("/api/applications/{applicationId}/analyze-ats")
    public ResponseEntity<ApplicantDetailDto> analyzeAts(@PathVariable Long applicationId,
                                                         Principal principal) {
        return ResponseEntity.ok(service.analyzeAts(applicationId, principal.getName()));
    }

    /**
     * POST /api/applications/{applicationId}/hire
     *
     * MANUAL HIRING — only reachable by the recruiter/manager who owns the
     * job. Only valid once the candidate has successfully completed every
     * prior stage (application status == INTERVIEW_PASSED). Rejected or
     * not-yet-evaluated candidates are rejected with a 409/400 by the
     * service layer (see GlobalExceptionHandler for mapping).
     */
    @PostMapping("/api/applications/{applicationId}/hire")
    public ResponseEntity<ApplicantDetailDto> hire(@PathVariable Long applicationId,
                                                   Principal principal) {
        return ResponseEntity.ok(service.hireCandidate(applicationId, principal.getName()));
    }

    public static class StatusUpdateRequest {
        private String status;
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}