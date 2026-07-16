package com.hirex.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helpers for the job-browse filtering system.
 *
 * These are used in two places:
 *   1. Job entity (@PrePersist / @PreUpdate) — derives normalized, queryable
 *      fields (workMode, experienceYears, salaryLpa) from the free-text
 *      fields a recruiter actually types in (location, experience, salary).
 *   2. JobService.browseJobs() — validates/whitelists whatever a client
 *      sends as filter query params so a typo'd or malicious value can never
 *      reach the database layer; unknown values are silently dropped rather
 *      than causing an error, per the "handle invalid values gracefully"
 *      requirement.
 */
public final class JobFilterUtil {

    private JobFilterUtil() {}

    private static final Pattern NUMBER = Pattern.compile("(\\d+(\\.\\d+)?)");

    public static final Set<String> VALID_JOB_TYPES =
            Set.of("Full Time", "Part Time", "Contract", "Internship");

    public static final Set<String> VALID_WORK_MODES =
            Set.of("On-site", "Remote", "Hybrid");

    public static final Set<String> VALID_EXPERIENCE_LEVELS =
            Set.of("Fresher", "0-1 Years", "1-3 Years", "3-5 Years", "5+ Years");

    public static final Set<String> VALID_SORTS =
            Set.of("most_relevant", "newest", "salary_high_low");

    /**
     * Work mode is not (yet) an explicit field recruiters fill in on the
     * "Post a Job" form, so it's inferred from the free-text location
     * (e.g. "Bengaluru / Remote" -> Remote). If an explicit, valid value is
     * already set on the job (e.g. once the manager UI grows a dedicated
     * field) that value always wins.
     */
    public static String resolveWorkMode(String explicitWorkMode, String location) {
        if (explicitWorkMode != null && VALID_WORK_MODES.contains(explicitWorkMode.trim())) {
            return explicitWorkMode.trim();
        }
        String loc = location == null ? "" : location.toLowerCase();
        if (loc.contains("remote")) return "Remote";
        if (loc.contains("hybrid")) return "Hybrid";
        return "On-site";
    }

    /**
     * Extracts the leading number of years from a free-text experience
     * string such as "2+ Years" or "Fresher". Returns null when the field
     * is blank/unset ("Any experience") so those jobs can be treated as a
     * match for every experience filter rather than excluded outright.
     */
    public static Integer parseExperienceYears(String experience) {
        if (experience == null || experience.isBlank()) return null;
        String e = experience.trim().toLowerCase();
        if (e.contains("fresher")) return 0;
        Matcher m = NUMBER.matcher(e);
        if (m.find()) {
            try {
                return (int) Double.parseDouble(m.group(1));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /**
     * Extracts the first numeric figure (in LPA) from a free-text salary
     * string such as "3-5 LPA" or "₹4,00,000". Returns null when unparseable
     * so the job isn't wrongly bucketed into a salary range.
     */
    public static Double parseSalaryLpa(String salary) {
        if (salary == null || salary.isBlank()) return null;
        Matcher m = NUMBER.matcher(salary.replace(",", ""));
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /** Inclusive [min, max] year bounds for a given experience-bucket label, or null if unrecognized. */
    public static int[] experienceBucketRange(String bucket) {
        if (bucket == null) return null;
        return switch (bucket.trim()) {
            case "Fresher" -> new int[]{0, 0};
            case "0-1 Years" -> new int[]{0, 1};
            case "1-3 Years" -> new int[]{1, 3};
            case "3-5 Years" -> new int[]{3, 5};
            case "5+ Years" -> new int[]{5, Integer.MAX_VALUE};
            default -> null;
        };
    }
}