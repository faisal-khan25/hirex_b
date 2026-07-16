package com.hirex.controller;

import com.hirex.dto.JobDto.JobBrowseResponse;
import com.hirex.dto.JobDto.JobRequest;
import com.hirex.dto.JobDto.JobResponse;
import com.hirex.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Public job browsing (no auth needed) — powers the Jobseeker "Browse
     * Jobs" page. Every param is optional and can be combined freely:
     *   - keyword     : matched against title / skills / description
     *   - location    : substring match against job location
     *   - jobType     : repeatable, e.g. jobType=Full Time&jobType=Contract
     *                   (a single comma-separated value also works)
     *   - workMode    : repeatable, e.g. workMode=Remote&workMode=Hybrid
     *   - experience  : repeatable bucket labels, e.g. "Fresher", "1-3 Years"
     *   - salaryMin / salaryMax : numeric LPA range
     *   - sortBy      : most_relevant (default) | newest | salary_high_low
     *   - page / size : pagination (0-indexed page, default size 12)
     *
     * Unknown/invalid values are dropped or clamped server-side rather than
     * causing an error — see JobService.browseJobs().
     */
    @GetMapping("/jobs/browse")
    public ResponseEntity<JobBrowseResponse> browse(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) List<String> jobType,
            @RequestParam(required = false) List<String> workMode,
            @RequestParam(required = false) List<String> experience,
            @RequestParam(required = false) Double salaryMin,
            @RequestParam(required = false) Double salaryMax,
            @RequestParam(required = false, defaultValue = "most_relevant") String sortBy,
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "12") Integer size) {

        log.info("GET /api/jobs/browse keyword='{}' location='{}' jobType={} workMode={} experience={} " +
                        "salaryMin={} salaryMax={} sortBy={} page={} size={}",
                keyword, location, jobType, workMode, experience, salaryMin, salaryMax, sortBy, page, size);

        JobBrowseResponse result = jobService.browseJobs(
                keyword, location, jobType, workMode, experience, salaryMin, salaryMax, sortBy, page, size);

        return ResponseEntity.ok(result);
    }

    // FILE: src/main/java/com/hirex/controller/JobController.java

    // ADD this new endpoint (keep existing /api/jobs/browse unchanged for candidates)
    @GetMapping("/manager/jobs/search")
    public ResponseEntity<List<JobResponse>> searchMyJobs(
            @RequestParam(required = false) String keyword,
            Principal principal) {
        if (keyword != null && !keyword.isBlank()) {
            return ResponseEntity.ok(jobService.searchManagerJobs(keyword, principal.getName()));
        }
        return ResponseEntity.ok(jobService.getManagerJobs(principal.getName()));
    }

    @PostMapping("/manager/jobs")
    public ResponseEntity<JobResponse> createJob(@RequestBody JobRequest req, Principal principal) {
        return ResponseEntity.ok(jobService.createJob(req, principal.getName()));
    }

    @PutMapping("/manager/jobs/{id}")
    public ResponseEntity<JobResponse> updateJob(@PathVariable Long id,
                                                 @RequestBody JobRequest req,
                                                 Principal principal) {
        return ResponseEntity.ok(jobService.updateJob(id, req, principal.getName()));
    }

    @DeleteMapping("/manager/jobs/{id}")
    public ResponseEntity<String> deleteJob(@PathVariable Long id, Principal principal) {
        jobService.deleteJob(id, principal.getName());
        return ResponseEntity.ok("Job removed");
    }

    @GetMapping("/manager/jobs")
    public ResponseEntity<List<JobResponse>> myJobs(Principal principal) {
        return ResponseEntity.ok(jobService.getManagerJobs(principal.getName()));
    }
}