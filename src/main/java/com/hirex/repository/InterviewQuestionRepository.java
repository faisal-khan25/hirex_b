package com.hirex.repository;
import com.hirex.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, Long> {

    // ADDED: Find questions by session
    List<InterviewQuestion> findBySessionIdOrderBySequenceNumber(Long sessionId);

    // ADDED: Find by question type
    List<InterviewQuestion> findBySessionIdAndQuestionType(Long sessionId, QuestionType questionType);

    // ADDED: Find follow-up questions
    @Query("SELECT q FROM InterviewQuestion q WHERE q.session.id = :sessionId AND q.isFollowUp = true")
    List<InterviewQuestion> findFollowUpQuestions(@Param("sessionId") Long sessionId);

    // Find next unanswered question.
    // FIX: the answered-question subquery is now scoped to this session
    // (a.session.id = :sessionId) instead of scanning every InterviewAnswer
    // row in the whole database. Functionally each question row already
    // belongs to exactly one session so this didn't cause cross-session
    // repeats, but on a large answers table it turned every "next question"
    // lookup into a full-table scan — a real contributor to slow question
    // loading as interview volume grew.
    @Query("SELECT q FROM InterviewQuestion q WHERE q.session.id = :sessionId " +
           "AND q.id NOT IN (SELECT a.question.id FROM InterviewAnswer a WHERE a.session.id = :sessionId) " +
           "ORDER BY q.sequenceNumber ASC LIMIT 1")
    Optional<InterviewQuestion> findNextUnansweredQuestion(@Param("sessionId") Long sessionId);

    // ADDED: Count by session
    long countBySessionId(Long sessionId);

    // ADDED: Count by type and session
    long countBySessionIdAndQuestionType(Long sessionId, QuestionType questionType);

    // ADDED: Find by generation source
    List<InterviewQuestion> findBySessionIdAndGeneratedBy(Long sessionId, QuestionGenerationSource generatedBy);

    // ADDED: Find questions asked in time range
    @Query("SELECT q FROM InterviewQuestion q WHERE q.session.id = :sessionId AND q.askedAt BETWEEN :startTime AND :endTime")
    List<InterviewQuestion> findAskedInTimeRange(@Param("sessionId") Long sessionId, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
}