package com.hirex.repository;

import com.hirex.entity.InterviewViolation;
import com.hirex.entity.ViolationSeverity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InterviewViolationRepository extends JpaRepository<InterviewViolation, Long> {

    /** Full violation log for a session, oldest first — used by the recruiter log view. */
    List<InterviewViolation> findByLiveSessionIdOrderByCreatedAtAsc(Long liveSessionId);

    /** Count of violations at/above a given severity — used for a quick session risk summary. */
    long countByLiveSessionIdAndSeverity(Long liveSessionId, ViolationSeverity severity);

    long countByLiveSessionId(Long liveSessionId);
}
