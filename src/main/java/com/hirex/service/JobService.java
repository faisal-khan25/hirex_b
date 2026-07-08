package com.hirex.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class JobService {

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

    public List<JobResponse> getAllActiveJobs() {
        return jobRepo.findByActiveTrue().stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<JobResponse> searchJobs(String keyword) {
        return jobRepo.findByTitleContainingIgnoreCaseAndActiveTrue(keyword)
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<JobResponse> getManagerJobs(String managerEmail) {
        User manager = userRepo.findByEmail(managerEmail).orElseThrow();
        Company company = companyRepo.findByManager(manager).orElseThrow();
        return jobRepo.findByCompanyAndActiveTrue(company)
                .stream().map(this::toResponse).collect(Collectors.toList());
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
        return r;
    }
}
