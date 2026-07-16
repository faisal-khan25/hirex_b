package com.hirex.entity;

import com.hirex.util.JobFilterUtil;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String skills;
    private String salary;
    private String location;
    private String jobType;
    private boolean active;

    // ATS-relevant fields
    private String experience;   // e.g. "2+ Years"
    private String education;    // e.g. "B.Tech in Computer Science"

    // ── Browse-filter fields ──────────────────────────────────────────
    // These are DERIVED (never set directly from user input) from the
    // free-text fields above, on every save, via deriveFilterFields().
    // Keeping them as real, indexable columns is what lets the jobseeker
    // "Browse Jobs" filters (work mode / experience range / salary range)
    // run as an actual WHERE clause instead of being computed in memory.
    @Column(name = "work_mode")
    private String workMode;          // On-site / Remote / Hybrid

    @Column(name = "experience_years")
    private Integer experienceYears;  // parsed minimum years, null = "Any experience"

    @Column(name = "salary_lpa")
    private Double salaryLpa;         // parsed numeric salary, null = "Not disclosed"

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @PrePersist
    public void prePersist() {
        postedAt = LocalDateTime.now();
        active = true;
        deriveFilterFields();
    }

    @PreUpdate
    public void preUpdate() {
        deriveFilterFields();
    }

    private void deriveFilterFields() {
        this.workMode = JobFilterUtil.resolveWorkMode(this.workMode, this.location);
        this.experienceYears = JobFilterUtil.parseExperienceYears(this.experience);
        this.salaryLpa = JobFilterUtil.parseSalaryLpa(this.salary);
    }

    public Job() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSkills() { return skills; }
    public void setSkills(String skills) { this.skills = skills; }

    public String getSalary() { return salary; }
    public void setSalary(String salary) { this.salary = salary; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(LocalDateTime postedAt) { this.postedAt = postedAt; }

    public String getExperience() { return experience; }
    public void setExperience(String experience) { this.experience = experience; }

    public String getEducation() { return education; }
    public void setEducation(String education) { this.education = education; }

    public String getWorkMode() { return workMode; }
    public void setWorkMode(String workMode) { this.workMode = workMode; }

    public Integer getExperienceYears() { return experienceYears; }
    public void setExperienceYears(Integer experienceYears) { this.experienceYears = experienceYears; }

    public Double getSalaryLpa() { return salaryLpa; }
    public void setSalaryLpa(Double salaryLpa) { this.salaryLpa = salaryLpa; }
}