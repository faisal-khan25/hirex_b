package com.hirex.util;

import com.hirex.entity.Job;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Composable, individually-optional filter predicates for Job.
 *
 * Every method returns {@code null} when the given filter value is empty,
 * so {@link #combine} can freely AND together any subset of active filters
 * without a chain of null-checks in the service layer. This is what lets
 * filters apply "individually and in combination" — each predicate is
 * independent and only contributes to the WHERE clause when the user
 * actually selected it.
 */
public final class JobSpecifications {

    private JobSpecifications() {}

    public static Specification<Job> isActive() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    public static Specification<Job> keyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        String like = "%" + keyword.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), like),
                cb.like(cb.lower(cb.coalesce(root.get("skills"), "")), like),
                cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like)
        );
    }

    public static Specification<Job> location(String location) {
        if (location == null || location.isBlank()) return null;
        String like = "%" + location.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(cb.coalesce(root.get("location"), "")), like);
    }

    public static Specification<Job> jobTypeIn(List<String> jobTypes) {
        if (jobTypes == null || jobTypes.isEmpty()) return null;
        return (root, query, cb) -> root.get("jobType").in(jobTypes);
    }

    public static Specification<Job> workModeIn(List<String> workModes) {
        if (workModes == null || workModes.isEmpty()) return null;
        return (root, query, cb) -> root.get("workMode").in(workModes);
    }

    /**
     * Matches jobs whose derived experienceYears falls inside ANY of the
     * selected bucket ranges. Jobs with no derivable experience ("Any
     * experience") always match, since they don't exclude any candidate.
     */
    public static Specification<Job> experienceIn(List<String> buckets) {
        if (buckets == null || buckets.isEmpty()) return null;
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.isNull(root.get("experienceYears")));
            for (String bucket : buckets) {
                int[] range = JobFilterUtil.experienceBucketRange(bucket);
                if (range == null) continue;
                preds.add(cb.between(root.get("experienceYears"), range[0], range[1]));
            }
            return cb.or(preds.toArray(new Predicate[0]));
        };
    }

    /** Inclusive salary range filter (LPA). Jobs with no parseable salary are excluded once a range is applied. */
    public static Specification<Job> salaryBetween(Double min, Double max) {
        if (min == null && max == null) return null;
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.isNotNull(root.get("salaryLpa")));
            if (min != null) preds.add(cb.ge(root.get("salaryLpa"), min));
            if (max != null) preds.add(cb.le(root.get("salaryLpa"), max));
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }

    /** ANDs together every non-null specification in the list; returns null (= "no filter") if all were null. */
    public static Specification<Job> combine(List<Specification<Job>> specs) {
        Specification<Job> result = null;
        for (Specification<Job> s : specs) {
            if (s == null) continue;
            result = (result == null) ? Specification.where(s) : result.and(s);
        }
        return result;
    }
}