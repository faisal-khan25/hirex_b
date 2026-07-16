package com.hirex.service;

import com.hirex.dto.JobDto.JobBrowseResponse;
import com.hirex.dto.JobDto.JobRequest;
import com.hirex.dto.JobDto.JobResponse;
import com.hirex.entity.Application;
import com.hirex.entity.Company;
import com.hirex.entity.Job;
import com.hirex.entity.User;
import com.hirex.repository.ApplicationRepository;
import com.hirex.repository.CompanyRepository;
import com.hirex.repository.JobRepository;
import com.hirex.repository.UserRepository;
import com.hirex.util.JobFilterUtil;
import com.hirex.util.JobSpecifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private static final int DEFAULT_PAGE_SIZE = 12;
    private static final int MAX_PAGE_SIZE = 100;

    private final JobRepository jobRepo;
    private final CompanyRepository companyRepo;
    private final UserRepository userRepo;
    private final ApplicationRepository appRepo;

    public JobService(JobRepository jobRepo, CompanyRepository companyRepo,
                      UserRepository userRepo, ApplicationRepository appRepo) {
        this.jobRepo = jobRepo;
        this.companyRepo = companyRepo;
        this.userRepo = userRepo;
        this.appRepo = appRepo;
    }

    public List<JobResponse> searchManagerJobs(String keyword, String managerEmail) {
        User manager = userRepo.findByEmail(managerEmail).orElseThrow();
        Company company = companyRepo.findByManager(manager).orElseThrow();
        return jobRepo.findByCompanyAndTitleContainingIgnoreCase(company, keyword)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public JobResponse createJob(JobRequest req, String managerEmail) {
        User manager = userRepo.findByEmail(managerEmail).orElseThrow();
        Company company = companyRepo.findByManager(manager)
                .orElseThrow(() -> new RuntimeException("Create company profile first"));

        Job job = new Job();
        job.setTitle(req.getTitle());
        job.setDescription(req.getDescription());
        job.setSkills(req.getSkills());
        job.setSalary(req.getSalary());
        job.setLocation(req.getLocation());
        job.setJobType(req.getJobType());
        job.setExperience(req.getExperience());
        job.setEducation(req.getEducation());
        job.setWorkMode(req.getWorkMode());
        job.setCompany(company);

        return toResponse(jobRepo.save(job));
    }

    public JobResponse updateJob(Long jobId, JobRequest req, String managerEmail) {
        Job job = jobRepo.findById(jobId).orElseThrow();
        User manager = userRepo.findByEmail(managerEmail).orElseThrow();
        Company company = companyRepo.findByManager(manager).orElseThrow();

        if (!job.getCompany().getId().equals(company.getId())) {
            throw new RuntimeException("Not authorized");
        }

        job.setTitle(req.getTitle());
        job.setDescription(req.getDescription());
        job.setSkills(req.getSkills());
        job.setSalary(req.getSalary());
        job.setLocation(req.getLocation());
        job.setJobType(req.getJobType());
        job.setExperience(req.getExperience());
        job.setEducation(req.getEducation());
        job.setWorkMode(req.getWorkMode());

        return toResponse(jobRepo.save(job));
    }

    /**
     * FIX: Deleting a job previously only removed the Job row
     * (jobRepo.delete(job)). Any Application rows pointing at that job
     * were left behind — either as orphaned rows (if the FK has no
     * cascade/is nullable) or they'd have blocked the delete outright
     * (if the FK is NOT NULL with no cascade). Either way, applicants
     * for a deleted job kept showing up in the Applicants views.
     *
     * Now: explicitly delete every Application tied to this job first,
     * then delete the job, all in one transaction so it's all-or-nothing.
     *
     * NOTE: if you also have InterviewSession, InterviewEvaluation, or
     * ChatMessage entities that reference Application by a NOT NULL FK
     * with no cascade, deleting the applications below could still throw
     * a constraint violation. If that happens, send me those repository
     * files and I'll extend this to delete them (interview sessions/
     * evaluations/chat messages) before the applications, in the same
     * transaction.
     */
    @Transactional
    public void deleteJob(Long jobId, String managerEmail) {
        Job job = jobRepo.findById(jobId).orElseThrow();
        User manager = userRepo.findByEmail(managerEmail).orElseThrow();
        Company company = companyRepo.findByManager(manager).orElseThrow();

        if (!job.getCompany().getId().equals(company.getId())) {
            throw new RuntimeException("Not authorized");
        }

        // Remove all applications (and therefore all applicants) tied to
        // this job before removing the job itself.
        List<Application> apps = appRepo.findByJob(job);
        if (!apps.isEmpty()) {
            appRepo.deleteAll(apps);
        }

        jobRepo.delete(job);
    }

    public List<JobResponse> getManagerJobs(String managerEmail) {
        User manager = userRepo.findByEmail(managerEmail).orElseThrow();
        Company company = companyRepo.findByManager(manager).orElseThrow();
        return jobRepo.findByCompanyAndActiveTrue(company)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * Powers GET /api/jobs/browse for the jobseeker panel.
     *
     * Every filter is optional and independent — keyword, location, job
     * type(s), work mode(s), experience bucket(s), and salary range can be
     * used alone or combined; JobSpecifications.combine() only ANDs in the
     * predicates that are actually present. Unknown/invalid values (a job
     * type that isn't one of the four real options, a negative salary, an
     * out-of-range page, etc.) are dropped or clamped here rather than
     * bubbling up as a 400/500, per "handle empty or invalid values
     * gracefully".
     */
    public JobBrowseResponse browseJobs(String keyword, String location, List<String> jobTypes,
                                        List<String> workModes, List<String> experienceLevels,
                                        Double salaryMin, Double salaryMax,
                                        String sortBy, Integer page, Integer size) {

        int safePage = (page == null || page < 0) ? 0 : page;
        int safeSize = (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;

        List<String> cleanJobTypes = whitelist(jobTypes, JobFilterUtil.VALID_JOB_TYPES);
        List<String> cleanWorkModes = whitelist(workModes, JobFilterUtil.VALID_WORK_MODES);
        List<String> cleanExperience = whitelist(experienceLevels, JobFilterUtil.VALID_EXPERIENCE_LEVELS);

        Double cleanMin = (salaryMin != null && salaryMin >= 0) ? salaryMin : null;
        Double cleanMax = (salaryMax != null && salaryMax >= 0) ? salaryMax : null;
        if (cleanMin != null && cleanMax != null && cleanMin > cleanMax) {
            // an inverted range ("min" bigger than "max") is still a valid
            // pair of numbers — swap instead of erroring out
            double tmp = cleanMin;
            cleanMin = cleanMax;
            cleanMax = tmp;
        }

        String cleanKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        String cleanLocation = (location != null && !location.isBlank()) ? location.trim() : null;

        Pageable pageable = PageRequest.of(safePage, safeSize, resolveSort(sortBy));

        // NOTE: java.util.Arrays.asList() (not List.of()) — several of the
        // specs above are intentionally null when their filter isn't
        // active, and List.of() throws a NullPointerException the instant
        // it sees a null element, which would 500 every single browse
        // request. Arrays.asList() allows nulls; combine() filters them out.
        Specification<Job> spec = JobSpecifications.combine(java.util.Arrays.asList(
                JobSpecifications.isActive(),
                JobSpecifications.keyword(cleanKeyword),
                JobSpecifications.location(cleanLocation),
                JobSpecifications.jobTypeIn(cleanJobTypes),
                JobSpecifications.workModeIn(cleanWorkModes),
                JobSpecifications.experienceIn(cleanExperience),
                JobSpecifications.salaryBetween(cleanMin, cleanMax)
        ));

        log.info("browseJobs: keyword='{}' location='{}' jobTypes={} workModes={} experience={} " +
                        "salary=[{}, {}] sortBy='{}' page={} size={}",
                cleanKeyword, cleanLocation, cleanJobTypes, cleanWorkModes, cleanExperience,
                cleanMin, cleanMax, sortBy, safePage, safeSize);

        Page<Job> resultPage = jobRepo.findAll(spec, pageable);
        log.debug("browseJobs: matched {} job(s) across {} page(s)",
                resultPage.getTotalElements(), resultPage.getTotalPages());

        JobBrowseResponse response = new JobBrowseResponse();
        response.setContent(resultPage.getContent().stream().map(this::toResponse).collect(Collectors.toList()));
        response.setPage(resultPage.getNumber());
        response.setSize(resultPage.getSize());
        response.setTotalElements(resultPage.getTotalElements());
        response.setTotalPages(resultPage.getTotalPages());
        response.setHasNext(resultPage.hasNext());
        return response;
    }

    private Sort resolveSort(String sortBy) {
        String s = sortBy == null ? "" : sortBy.trim().toLowerCase();
        return switch (s) {
            case "newest" -> Sort.by(Sort.Direction.DESC, "postedAt");
            case "salary_high_low" -> Sort.by(Sort.Direction.DESC, "salaryLpa")
                    .and(Sort.by(Sort.Direction.DESC, "postedAt"));
            default -> Sort.by(Sort.Direction.DESC, "postedAt"); // "most_relevant" / unrecognized
        };
    }

    private List<String> whitelist(List<String> values, Set<String> allowed) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .filter(allowed::contains)
                .distinct()
                .collect(Collectors.toList());
    }

    private JobResponse toResponse(Job job) {
        JobResponse r = new JobResponse();
        r.setId(job.getId());
        r.setTitle(job.getTitle());
        r.setDescription(job.getDescription());
        r.setSkills(job.getSkills());
        r.setSalary(job.getSalary());
        r.setLocation(job.getLocation());
        r.setJobType(job.getJobType());
        r.setActive(job.isActive());
        r.setPostedAt(job.getPostedAt() != null ? job.getPostedAt().toString() : "");
        r.setCompanyName(job.getCompany().getName());
        r.setCompanyLogo(job.getCompany().getLogoUrl());
        r.setCompanyId(job.getCompany().getId());
        r.setExperience(job.getExperience());
        r.setEducation(job.getEducation());
        r.setWorkMode(job.getWorkMode());
        return r;
    }
}