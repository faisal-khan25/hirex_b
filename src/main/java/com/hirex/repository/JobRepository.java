package com.hirex.repository;

import com.hirex.entity.Company;
import com.hirex.entity.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {

    // PERF: Job.company is now LAZY (see Job entity — it used to eagerly
    // pull the whole Company row, which itself eagerly pulled the manager's
    // User row, for every single job). This is the public job-browse query
    // used on Home/dashboard, so it JOIN FETCHes company explicitly instead
    // — one query instead of 1 + N.
    @Query("SELECT j FROM Job j JOIN FETCH j.company WHERE j.active = true")
    List<Job> findByActiveTrue();

    List<Job> findByCompany(Company company);

    @Query("SELECT j FROM Job j JOIN FETCH j.company WHERE j.active = true " +
            "AND LOWER(j.title) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Job> findByTitleContainingIgnoreCaseAndActiveTrue(@Param("keyword") String keyword);

    List<Job> findByCompanyAndTitleContainingIgnoreCase(Company company, String keyword);

    /**
     * FIX: Find a job only if it belongs to the given manager (via company).
     * Used in ApplicationService.getApplicantsForJob() to enforce ownership
     * before returning any applicant data.
     */
    @Query("""
            SELECT j FROM Job j
            JOIN j.company c
            WHERE j.id = :jobId
            AND c.manager.email = :managerEmail
            """)
    Optional<Job> findByIdAndManagerEmail(@Param("jobId") Long jobId,
                                          @Param("managerEmail") String managerEmail);
    List<Job> findByCompanyAndActiveTrue(Company company);

    /**
     * FILTER FIX: powers GET /api/jobs/browse. JpaSpecificationExecutor lets
     * JobService build the WHERE clause dynamically (keyword, location,
     * job type, work mode, experience range, salary range — any subset,
     * combined with AND) instead of the old hardcoded single-keyword query.
     *
     * The @EntityGraph is required here: a plain Specification-driven
     * findAll(spec, pageable) would otherwise lazy-load Company (and then
     * its manager) once PER ROW while building each JobResponse — an N+1
     * query bug. A JOIN FETCH can't be used directly with Specification +
     * Pageable (Hibernate can't correctly paginate a fetch-joined
     * collection), so an EntityGraph is the correct fix: it fetches
     * `company` in the same query without breaking COUNT/LIMIT pagination.
     */
    @Override
    @EntityGraph(attributePaths = {"company"})
    Page<Job> findAll(Specification<Job> spec, Pageable pageable);

}