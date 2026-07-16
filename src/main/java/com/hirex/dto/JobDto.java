package com.hirex.dto;

import java.util.List;

public class JobDto {

    public static class JobRequest {
        private String title;
        private String description;
        private String skills;
        private String salary;
        private String location;
        private String jobType;
        private String experience;
        private String education;
        private String workMode;

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

        public String getExperience() { return experience; }
        public void setExperience(String experience) { this.experience = experience; }

        public String getEducation() { return education; }
        public void setEducation(String education) { this.education = education; }

        public String getWorkMode() { return workMode; }
        public void setWorkMode(String workMode) { this.workMode = workMode; }
    }

    public static class JobResponse {
        private Long id;
        private String title;
        private String description;
        private String skills;
        private String salary;
        private String location;
        private String jobType;
        private boolean active;
        private String postedAt;
        private String companyName;
        private String companyLogo;
        private Long companyId;
        private String experience;
        private String education;
        private String workMode;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

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

        public String getPostedAt() { return postedAt; }
        public void setPostedAt(String postedAt) { this.postedAt = postedAt; }

        public String getCompanyName() { return companyName; }
        public void setCompanyName(String companyName) { this.companyName = companyName; }

        public String getCompanyLogo() { return companyLogo; }
        public void setCompanyLogo(String companyLogo) { this.companyLogo = companyLogo; }

        public Long getCompanyId() { return companyId; }
        public void setCompanyId(Long companyId) { this.companyId = companyId; }

        public String getExperience() { return experience; }
        public void setExperience(String experience) { this.experience = experience; }

        public String getEducation() { return education; }
        public void setEducation(String education) { this.education = education; }

        public String getWorkMode() { return workMode; }
        public void setWorkMode(String workMode) { this.workMode = workMode; }
    }

    /**
     * Paginated wrapper returned by GET /api/jobs/browse. Carries enough
     * metadata (page, size, totalElements, totalPages, hasNext) for the
     * frontend to render pagination controls and preserve the current
     * filter state while paging, without a full page reload.
     */
    public static class JobBrowseResponse {
        private List<JobResponse> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean hasNext;

        public List<JobResponse> getContent() { return content; }
        public void setContent(List<JobResponse> content) { this.content = content; }

        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }

        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }

        public long getTotalElements() { return totalElements; }
        public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

        public int getTotalPages() { return totalPages; }
        public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

        public boolean isHasNext() { return hasNext; }
        public void setHasNext(boolean hasNext) { this.hasNext = hasNext; }
    }
}