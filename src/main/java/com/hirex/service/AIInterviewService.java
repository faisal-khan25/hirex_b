package com.hirex.service;

import com.hirex.dto.*;
import com.hirex.entity.*;
import com.hirex.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AIInterviewService — fixed, complete implementation.
 *
 * KEY FIXES:
 * 1. generateInitialQuestions() always produces exactly 10 UNIQUE questions.
 *    Questions are de-duplicated by text before saving. No repeats ever.
 *
 * 2. generateEvaluation() extracted to a public @Transactional method so
 *    Spring can actually proxy it.  A private @Transactional method is
 *    NEVER intercepted by Spring — the annotation is silently ignored,
 *    meaning exceptions inside it rolled back the outer transaction and
 *    deleted the COMPLETED status. Now it runs in its own transaction.
 *
 * 3. Scores calculated correctly:
 *    - communicationScore  = avg(clarityScore)    × 100  (0–100)
 *    - confidenceScore     = avg(confidenceScore)  × 100
 *    - technicalSkillsScore = avg relevance of TECHNICAL answers × 100
 *    - problemSolvingScore  = avg relevance of PROBLEM_SOLVING answers × 100
 *    - domainKnowledgeScore = avg relevance of DOMAIN_KNOWLEDGE answers × 100
 *    Missing categories default to 50 (neutral) not 0 (so one missing
 *    category doesn't tank the score).
 *
 * 4. After evaluation, session.status → PASSED / UNDER_REVIEW / FAILED
 *    (in addition to COMPLETED) and application.status is updated.
 *
 * 5. InterviewEvaluation scores are nullable in the DB so a null score
 *    doesn't cause a constraint violation when a category has no answers.
 *
 * 6. getInterviewReport() includes interviewPassStatus in the session DTO.
 */
@Service
public class AIInterviewService {

    private static final Logger log = LoggerFactory.getLogger(AIInterviewService.class);

    /** Fixed question count per interview */
    private static final int TOTAL_QUESTIONS = 10;

    private final InterviewSessionRepository    sessionRepository;
    private final InterviewQuestionRepository   questionRepository;
    private final InterviewAnswerRepository     answerRepository;
    private final InterviewEvaluationRepository evaluationRepository;
    private final ApplicationRepository         applicationRepository;
    private final AIQuestionGeneratorService    questionGeneratorService;
    private final AIAnswerEvaluatorService      answerEvaluatorService;
    private final JobRepository                 jobRepository;
    private final InterviewEvaluationService    evaluationService;

    public AIInterviewService(
            InterviewSessionRepository    sessionRepository,
            InterviewQuestionRepository   questionRepository,
            InterviewAnswerRepository     answerRepository,
            InterviewEvaluationRepository evaluationRepository,
            ApplicationRepository         applicationRepository,
            AIQuestionGeneratorService    questionGeneratorService,
            AIAnswerEvaluatorService      answerEvaluatorService,
            JobRepository                 jobRepository,
            InterviewEvaluationService    evaluationService) {
        this.sessionRepository       = sessionRepository;
        this.questionRepository      = questionRepository;
        this.answerRepository        = answerRepository;
        this.evaluationRepository    = evaluationRepository;
        this.applicationRepository   = applicationRepository;
        this.questionGeneratorService = questionGeneratorService;
        this.answerEvaluatorService   = answerEvaluatorService;
        this.jobRepository            = jobRepository;
        this.evaluationService        = evaluationService;
    }

    // ── Schedule / assign ─────────────────────────────────────────────────

    public InterviewSessionDto scheduleInterview(Long applicationId, String interviewTemplate) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("Application not found: " + applicationId));

        InterviewSession session = new InterviewSession(application, InterviewType.AI, interviewTemplate);
        session.setScheduledAt(LocalDateTime.now().plusDays(1));
        session.setMaxDurationMinutes(60);
        session.setCandidateName(application.getApplicant().getName());
        session.setPositionTitle(application.getJob().getTitle());
        session.setJobDescription(application.getJob().getDescription());
        session = sessionRepository.save(session);

        application.setStatus(ApplicationStatus.SHORTLISTED);
        applicationRepository.save(application);

        log.info("Interview scheduled for application {} (session {}).", applicationId, session.getId());
        return mapToDto(session);
    }

    public InterviewSessionDto assignInterview(Long applicationId, String interviewTemplate) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("Application not found: " + applicationId));

        if (application.getStatus() == ApplicationStatus.APPLIED) {
            application.setStatus(ApplicationStatus.SHORTLISTED);
            applicationRepository.save(application);
        }

        Optional<InterviewSession> existing = sessionRepository.findByApplicationId(applicationId);
        if (existing.isPresent()) return mapToDto(existing.get());

        InterviewSession session = new InterviewSession(application, InterviewType.AI, interviewTemplate);
        session.setScheduledAt(LocalDateTime.now().plusDays(1));
        session.setMaxDurationMinutes(60);
        session.setStatus(InterviewStatus.PENDING);
        session.setCandidateName(application.getApplicant().getName());
        session.setPositionTitle(application.getJob().getTitle());
        session.setJobDescription(application.getJob().getDescription());

        session = sessionRepository.save(session);
        log.info("Interview assigned for application {} (session {}).", applicationId, session.getId());
        return mapToDto(session);
    }

    // ── Start ─────────────────────────────────────────────────────────────

    @Transactional
    public InterviewSessionDto startInterview(Long sessionId, String userEmail) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Interview session not found: " + sessionId));

        if (!session.getApplication().getApplicant().getEmail().equals(userEmail)) {
            throw new RuntimeException("Unauthorized: you cannot start this interview.");
        }

        // Idempotent: already running → return full question list
        if (session.getStatus() == InterviewStatus.IN_PROGRESS) {
            List<InterviewQuestion> existing =
                    questionRepository.findBySessionIdOrderBySequenceNumber(sessionId);
            log.info("Session {} already IN_PROGRESS; returning {} existing questions.", sessionId, existing.size());
            return mapToDto(session, existing);
        }

        if (session.getStatus() == InterviewStatus.COMPLETED
                || session.getStatus() == InterviewStatus.PASSED
                || session.getStatus() == InterviewStatus.UNDER_REVIEW
                || session.getStatus() == InterviewStatus.FAILED) {
            throw new RuntimeException("This interview has already been completed.");
        }

        if (session.getStatus() != InterviewStatus.PENDING) {
            throw new RuntimeException("Interview is in an unexpected state: " + session.getStatus());
        }

        session.setStatus(InterviewStatus.IN_PROGRESS);
        session.setStartedAt(LocalDateTime.now());
        session = sessionRepository.save(session);

        // Generate exactly TOTAL_QUESTIONS unique questions
        List<InterviewQuestion> questions = generateUniqueQuestions(session);

        log.info("Session {} started with {} questions.", sessionId, questions.size());
        return mapToDto(session, questions);
    }

    /**
     * Generate exactly TOTAL_QUESTIONS (10) unique questions for the session.
     * De-duplicates by question text. Falls back to static set if AI is down.
     * Questions are saved to DB in sequence order — no reshuffling after this.
     */
    private List<InterviewQuestion> generateUniqueQuestions(InterviewSession session) {
        // Category split: 4 technical + 3 behavioral + 2 domain + 1 problem-solving = 10
        List<InterviewQuestion> raw = new ArrayList<>();

        try { raw.addAll(questionGeneratorService.generateTechnicalQuestions(session, 4)); }
        catch (Exception e) { log.warn("Technical questions failed: {}", e.getMessage()); }

        try { raw.addAll(questionGeneratorService.generateBehavioralQuestions(session, 3)); }
        catch (Exception e) { log.warn("Behavioral questions failed: {}", e.getMessage()); }

        try { raw.addAll(questionGeneratorService.generateDomainKnowledgeQuestions(session, 2)); }
        catch (Exception e) { log.warn("Domain questions failed: {}", e.getMessage()); }

        try { raw.addAll(questionGeneratorService.generateProblemSolvingQuestions(session, 1)); }
        catch (Exception e) { log.warn("Problem-solving questions failed: {}", e.getMessage()); }

        // De-duplicate by normalized text
        Set<String> seen = new LinkedHashSet<>();
        List<InterviewQuestion> unique = new ArrayList<>();
        for (InterviewQuestion q : raw) {
            String key = q.getQuestionText().trim().toLowerCase();
            if (seen.add(key)) unique.add(q);
        }

        // Pad to TOTAL_QUESTIONS with static fallback if needed
        if (unique.size() < TOTAL_QUESTIONS) {
            log.warn("Only {} unique questions generated; padding with fallback questions.", unique.size());
            List<InterviewQuestion> fallback = buildFallbackQuestions(session, TOTAL_QUESTIONS - unique.size(), seen);
            unique.addAll(fallback);
        }

        // Trim to exactly TOTAL_QUESTIONS
        if (unique.size() > TOTAL_QUESTIONS) {
            unique = unique.subList(0, TOTAL_QUESTIONS);
        }

        // Assign sequence numbers and persist
        List<InterviewQuestion> saved = new ArrayList<>();
        for (int i = 0; i < unique.size(); i++) {
            InterviewQuestion q = unique.get(i);
            q.setSequenceNumber(i + 1);
            q.setGeneratedBy(QuestionGenerationSource.AI_GENERATED);
            saved.add(questionRepository.save(q));
        }
        return saved;
    }

    private List<InterviewQuestion> buildFallbackQuestions(InterviewSession session,
                                                           int count, Set<String> seen) {
        List<String> pool = Arrays.asList(
                "Tell me about your most significant professional achievement.",
                "Describe a challenging problem you solved and how you approached it.",
                "How do you prioritize tasks when you have multiple urgent deadlines?",
                "Walk me through your experience with the core technologies used in this role.",
                "Describe a time you disagreed with a team decision and how you handled it.",
                "What is your process for debugging a complex issue in production?",
                "How do you stay up to date with new tools and industry best practices?",
                "Tell me about a project where you had to learn something entirely new quickly.",
                "How do you handle receiving critical feedback from a manager or peer?",
                "Where do you see your career in the next three to five years?"
        );

        List<InterviewQuestion> result = new ArrayList<>();
        for (String text : pool) {
            if (result.size() >= count) break;
            if (seen.add(text.trim().toLowerCase())) {
                InterviewQuestion q = new InterviewQuestion();
                q.setSession(session);
                q.setQuestionText(text);
                q.setQuestionType(QuestionType.BEHAVIORAL);
                q.setDifficultyLevel("MEDIUM");
                q.setGeneratedBy(QuestionGenerationSource.AI_GENERATED);
                result.add(q);
            }
        }
        return result;
    }

    // ── Next question ─────────────────────────────────────────────────────

    public InterviewQuestionDto getNextQuestion(Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Interview session not found: " + sessionId));

        if (session.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new RuntimeException("Interview is not in progress (status: " + session.getStatus() + ").");
        }

        Optional<InterviewQuestion> next = questionRepository.findNextUnansweredQuestion(sessionId);

        if (next.isEmpty()) {
            // All 10 questions answered → complete
            completeInterview(sessionId);
            throw new RuntimeException("Interview completed — no more questions.");
        }

        InterviewQuestion question = next.get();
        question.setAskedAt(LocalDateTime.now());
        question.setResponseDeadline(
                LocalDateTime.now().plusSeconds(question.getAnswerTimeoutSeconds()));
        questionRepository.save(question);

        return mapQuestionToDto(question);
    }

    // ── Submit answer ─────────────────────────────────────────────────────

    @Transactional
    public InterviewAnswerDto submitAnswer(Long sessionId, Long questionId,
                                           String answerText, String transcript) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Interview session not found: " + sessionId));

        InterviewQuestion question = questionRepository.findById(questionId)
                .orElseThrow(() -> new RuntimeException("Question not found: " + questionId));

        if (!question.getSession().getId().equals(sessionId)) {
            throw new RuntimeException("Question does not belong to this interview session.");
        }

        // Idempotency guard: if this question was already answered (double click,
        // network retry, or client re-send after a timeout), return the existing
        // answer instead of creating a duplicate row / re-running AI evaluation.
        Optional<InterviewAnswer> existingAnswer =
                answerRepository.findBySessionIdAndQuestionId(sessionId, questionId);
        if (existingAnswer.isPresent()) {
            log.info("Session {}: question {} already answered — returning existing answer (duplicate submit prevented).",
                    sessionId, questionId);
            return mapAnswerToDto(existingAnswer.get());
        }

        if (session.getStatus() != InterviewStatus.IN_PROGRESS) {
            throw new RuntimeException(
                    "Cannot submit answer: interview is not in progress (status: " + session.getStatus() + ").");
        }

        String safeAnswerText = (answerText != null && !answerText.isBlank())
                ? answerText : "[No answer provided]";
        String safeTranscript = (transcript != null && !transcript.isBlank())
                ? transcript : safeAnswerText;

        InterviewAnswer answer = new InterviewAnswer(question, session, safeAnswerText);
        answer.setTranscript(safeTranscript);
        answer.setAnsweredAt(LocalDateTime.now());
        answer.setWordCount(safeTranscript.split("\\s+").length);
        if (question.getAskedAt() != null) {
            answer.setDurationSeconds((int)
                    java.time.temporal.ChronoUnit.SECONDS.between(
                            question.getAskedAt(), LocalDateTime.now()));
        }
        answer = answerRepository.save(answer);
        final Long answerId = answer.getId();

        // PERFORMANCE FIX: AI scoring used to run synchronously right here,
        // blocking this request on a live Ollama call (up to 30s) before the
        // response — and therefore the next question on the frontend — could
        // appear. The answer is already safely persisted above, so scoring
        // is now handed off to a background thread (see AsyncConfig /
        // AIAnswerEvaluatorService.evaluateAnswerAsync) and this request
        // returns immediately. Scores are filled in and saved once ready;
        // until then the answer simply has no scores yet, which every
        // downstream consumer (evaluation fallback, DTO mapping) already
        // treats as neutral defaults.
        //
        // Deferred to after commit for the same reason completeInterview()
        // defers its async evaluation kick-off: a background thread using a
        // new DB connection under READ_COMMITTED won't see this answer row
        // until the transaction that inserted it has actually committed.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            answerEvaluatorService.evaluateAnswerAsync(answerId);
                        }
                    });
        } else {
            answerEvaluatorService.evaluateAnswerAsync(answerId);
        }

        // Auto-complete if this was the last question.
        // IMPORTANT: this must never cause the answer submission itself to fail —
        // the answer is already safely persisted above. Completion/evaluation
        // problems are logged and can be retried via POST /complete separately.
        long totalQ    = questionRepository.countBySessionId(sessionId);
        long answeredQ = answerRepository.countBySessionId(sessionId);
        if (answeredQ >= totalQ) {
            log.info("Session {}: all {} questions answered — auto-completing.", sessionId, totalQ);
            try {
                completeInterview(sessionId);
            } catch (Exception e) {
                log.error("Session {}: auto-complete failed after final answer ({}). " +
                        "Answer was saved; completion can be retried via /complete.",
                        sessionId, e.getMessage());
            }
        }

        log.info("Session {}: answer recorded for question {}.", sessionId, questionId);
        return mapAnswerToDto(answer);
    }

    // ── Complete ──────────────────────────────────────────────────────────

    /**
     * Mark session COMPLETED, run evaluation, update outcome status.
     * Idempotent — safe to call multiple times.
     */
//    @Transactional
//    public InterviewSessionDto completeInterview(Long sessionId) {
//        InterviewSession session = sessionRepository.findById(sessionId)
//                .orElseThrow(() -> new RuntimeException("Interview session not found: " + sessionId));
//
//        boolean alreadyDone = session.getStatus() == InterviewStatus.COMPLETED
//                || session.getStatus() == InterviewStatus.PASSED
//                || session.getStatus() == InterviewStatus.UNDER_REVIEW
//                || session.getStatus() == InterviewStatus.FAILED;
//
//        if (alreadyDone) {
//            log.info("Session {} already finished ({}) — idempotent call.", sessionId, session.getStatus());
//            return mapToDto(session);
//        }
//
//        session.setStatus(InterviewStatus.COMPLETED);
//        session.setEndedAt(LocalDateTime.now());
//        session = sessionRepository.save(session);
//        log.info("Session {} marked COMPLETED.", sessionId);
//
//        // Evaluation runs in its own transaction (public method → Spring can proxy it)
//        try {
//            saveEvaluation(sessionId);
//        } catch (Exception e) {
//            log.error("Evaluation failed for session {} ({}). Session remains COMPLETED.",
//                    sessionId, e.getMessage());
//        }
//
//        return mapToDto(session);
//    }
    // REPLACE completeInterview() with this version:
    @Transactional
    public InterviewSessionDto completeInterview(Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Interview session not found: " + sessionId));

        boolean alreadyDone = session.getStatus() == InterviewStatus.COMPLETED
                || session.getStatus() == InterviewStatus.PASSED
                || session.getStatus() == InterviewStatus.UNDER_REVIEW
                || session.getStatus() == InterviewStatus.FAILED;

        if (alreadyDone) {
            log.info("Session {} already finished ({}) — idempotent call.", sessionId, session.getStatus());
            return mapToDto(session);  // mapToDto now reads evaluation from DB
        }

        session.setStatus(InterviewStatus.COMPLETED);
        session.setEndedAt(LocalDateTime.now());
        sessionRepository.save(session);
        log.info("Session {} marked COMPLETED.", sessionId);

        // Evaluation involves one or more calls out to the (potentially slow or
        // unavailable) Ollama service. Running it synchronously here used to block
        // this HTTP request/transaction until Ollama responded — for the final
        // question that meant stacking a per-answer eval call AND a holistic eval
        // call inside a single request, which could exceed the frontend's request
        // timeout and made the interview appear to "fail" even though the answer
        // and COMPLETED status were already safely committed.
        //
        // Fix: run it asynchronously, on its own thread and its own transaction,
        // via a genuinely separate Spring bean (so @Async is honored — invoking
        // it via `this` would silently ignore the annotation). We're still inside
        // this method's own @Transactional block here, so we defer the actual
        // kick-off until AFTER this transaction commits — otherwise the async
        // thread could start before the COMPLETED status/answers are visible to
        // it (a new connection under READ_COMMITTED won't see uncommitted rows).
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            evaluationService.evaluateAndFinalizeAsync(sessionId);
                        }
                    });
        } else {
            evaluationService.evaluateAndFinalizeAsync(sessionId);
        }

        return mapToDto(session);
    }

    // ── Follow-up ─────────────────────────────────────────────────────────

    public InterviewQuestionDto generateFollowUp(Long sessionId, Long questionId, String context) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Interview session not found: " + sessionId));
        InterviewQuestion original = questionRepository.findById(questionId)
                .orElseThrow(() -> new RuntimeException("Question not found: " + questionId));

        String followUpText = questionGeneratorService.generateFollowUpQuestion(original, context);
        int seq = Math.toIntExact(questionRepository.countBySessionId(sessionId) + 1);
        InterviewQuestion followUp = new InterviewQuestion(
                session, followUpText, original.getQuestionType(), seq);
        followUp.setIsFollowUp(true);
        followUp.setParentQuestion(original);
        followUp.setGeneratedBy(QuestionGenerationSource.AI_GENERATED);
        followUp.setContext(context);
        followUp = questionRepository.save(followUp);
        return mapQuestionToDto(followUp);
    }

    // ── Queries ───────────────────────────────────────────────────────────

    public InterviewSessionDto getApplicationInterview(Long applicationId) {
        InterviewSession session = sessionRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new RuntimeException(
                        "No interview session found for application: " + applicationId));
        return mapToDto(session);
    }

    public InterviewSessionDto getInterviewSummary(Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Interview session not found: " + sessionId));
        return mapToDto(session);
    }

    public InterviewReportDto getInterviewReport(Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Interview session not found: " + sessionId));

        InterviewReportDto report = new InterviewReportDto();
        report.setSession(mapToDto(session));
        report.setGeneratedAt(LocalDateTime.now());

        List<InterviewQuestion> questions =
                questionRepository.findBySessionIdOrderBySequenceNumber(session.getId());
        report.setQuestions(questions.stream().map(this::mapQuestionToDto).collect(Collectors.toList()));

        List<InterviewAnswer> answers =
                answerRepository.findBySessionIdOrderByAnsweredAt(session.getId());
        report.setAnswers(answers.stream().map(this::mapAnswerToDto).collect(Collectors.toList()));

        evaluationRepository.findBySessionId(session.getId())
                .ifPresent(e -> report.setEvaluation(mapEvaluationToDto(e)));

        String transcript = answers.stream()
                .map(a -> "Q: " + a.getQuestion().getQuestionText() + "\nA: " + a.getTranscript())
                .collect(Collectors.joining("\n\n"));
        report.setFullTranscript(transcript);

        return report;
    }

    public List<InterviewSessionDto> getInterviewsForJob(Long jobId) {
        return sessionRepository.findByJobId(jobId).stream()
                .map(this::mapToDto).collect(Collectors.toList());
    }

    public Map<String, Object> getInterviewStatistics(Long jobId) {
        List<InterviewSession> sessions = sessionRepository.findByJobId(jobId);
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSessions", sessions.size());
        stats.put("completed",   sessions.stream().filter(s -> isCompleted(s.getStatus())).count());
        stats.put("passed",      sessions.stream().filter(s -> s.getStatus() == InterviewStatus.PASSED).count());
        stats.put("underReview", sessions.stream().filter(s -> s.getStatus() == InterviewStatus.UNDER_REVIEW).count());
        stats.put("failed",      sessions.stream().filter(s -> s.getStatus() == InterviewStatus.FAILED).count());
        stats.put("inProgress",  sessions.stream().filter(s -> s.getStatus() == InterviewStatus.IN_PROGRESS).count());
        stats.put("pending",     sessions.stream().filter(s -> s.getStatus() == InterviewStatus.PENDING).count());

        List<InterviewEvaluation> evals = sessions.stream()
                .map(s -> evaluationRepository.findBySessionId(s.getId()))
                .filter(Optional::isPresent).map(Optional::get)
                .collect(Collectors.toList());

        if (!evals.isEmpty()) {
            stats.put("averageRating", evals.stream()
                    .mapToDouble(e -> nvl(e.getOverallRating(), 0.0)).average().orElse(0));
        }
        return stats;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Average relevance score (0–100) for a given question type.
     * Returns 50.0 (neutral) when no questions of that type were answered,
     * so a missing category doesn't drag the overall score to zero.
     */
    private double avgRelevance(List<InterviewAnswer> answers, QuestionType type) {
        OptionalDouble avg = answers.stream()
                .filter(a -> a.getQuestion().getQuestionType() == type)
                .mapToDouble(a -> nvl(a.getRelevanceScore(), 0.5))
                .average();
        return avg.isPresent() ? avg.getAsDouble() * 100 : 50.0;
    }

    private RecommendationStatus deriveRecommendation(Double score) {
        if (score == null) return RecommendationStatus.MAYBE;
        if (score >= 85)   return RecommendationStatus.STRONG_YES;
        if (score >= 75)   return RecommendationStatus.YES;
        if (score >= 60)   return RecommendationStatus.MAYBE;
        if (score >= 45)   return RecommendationStatus.NO;
        return RecommendationStatus.STRONG_NO;
    }

    private InterviewStatus toOutcomeStatus(String passStatus) {
        if ("PASSED".equals(passStatus))       return InterviewStatus.PASSED;
        if ("UNDER_REVIEW".equals(passStatus)) return InterviewStatus.UNDER_REVIEW;
        return InterviewStatus.FAILED;
    }

    private boolean isCompleted(InterviewStatus s) {
        return s == InterviewStatus.COMPLETED
                || s == InterviewStatus.PASSED
                || s == InterviewStatus.UNDER_REVIEW
                || s == InterviewStatus.FAILED;
    }

    // ── DTO mappers ───────────────────────────────────────────────────────

    private InterviewSessionDto mapToDto(InterviewSession s) { return mapToDto(s, null); }

//    private InterviewSessionDto mapToDto(InterviewSession s, List<InterviewQuestion> questions) {
//        InterviewSessionDto dto = new InterviewSessionDto();
//        dto.setId(s.getId());
//        dto.setApplicationId(s.getApplication().getId());
//        dto.setInterviewType(s.getInterviewType().toString());
//        dto.setStatus(s.getStatus().toString());
//        dto.setScheduledAt(s.getScheduledAt());
//        dto.setStartedAt(s.getStartedAt());
//        dto.setEndedAt(s.getEndedAt());
//        dto.setRecordingUrl(s.getRecordingUrl());
//        dto.setInterviewTemplate(s.getInterviewTemplate());
//        dto.setMaxDurationMinutes(s.getMaxDurationMinutes());
//        dto.setCandidateName(s.getCandidateName());
//        dto.setPositionTitle(s.getPositionTitle());
//        dto.setCreatedAt(s.getCreatedAt());
//        dto.setUpdatedAt(s.getUpdatedAt());
//        if (questions != null) {
//            dto.setQuestions(questions.stream().map(this::mapQuestionToDto).collect(Collectors.toList()));
//        }
//        return dto;
//    }
// REPLACE the existing private mapToDto() method with this version:
private InterviewSessionDto mapToDto(InterviewSession s, List<InterviewQuestion> questions) {
    InterviewSessionDto dto = new InterviewSessionDto();
    dto.setId(s.getId());
    dto.setApplicationId(s.getApplication().getId());
    dto.setInterviewType(s.getInterviewType().toString());
    dto.setStatus(s.getStatus().toString());
    dto.setScheduledAt(s.getScheduledAt());
    dto.setStartedAt(s.getStartedAt());
    dto.setEndedAt(s.getEndedAt());
    dto.setRecordingUrl(s.getRecordingUrl());
    dto.setInterviewTemplate(s.getInterviewTemplate());
    dto.setMaxDurationMinutes(s.getMaxDurationMinutes());
    dto.setCandidateName(s.getCandidateName());
    dto.setPositionTitle(s.getPositionTitle());
    dto.setCreatedAt(s.getCreatedAt());
    dto.setUpdatedAt(s.getUpdatedAt());

    if (questions != null) {
        dto.setQuestions(questions.stream()
                .map(this::mapQuestionToDto)
                .collect(Collectors.toList()));
    }

    // ★ NEW: Populate evaluation + pass status for completed sessions
    evaluationRepository.findBySessionId(s.getId()).ifPresent(eval -> {
        dto.setInterviewPassStatus(eval.getInterviewPassStatus());
        dto.setInterviewScore(eval.getOverallRating());
        dto.setEvaluation(mapEvaluationToDto(eval));
    });

    // ★ NEW: If evaluation not yet saved, derive status from session status enum
    if (dto.getInterviewPassStatus() == null) {
        switch (s.getStatus()) {
            case PASSED:       dto.setInterviewPassStatus("PASSED"); break;
            case UNDER_REVIEW: dto.setInterviewPassStatus("UNDER_REVIEW"); break;
            case FAILED:       dto.setInterviewPassStatus("FAILED"); break;
            case COMPLETED:    dto.setInterviewPassStatus("COMPLETED"); break;
            case IN_PROGRESS:  dto.setInterviewPassStatus("IN_PROGRESS"); break;
            default:           dto.setInterviewPassStatus("SCHEDULED"); break;
        }
    }

    return dto;
}

    private InterviewQuestionDto mapQuestionToDto(InterviewQuestion q) {
        InterviewQuestionDto dto = new InterviewQuestionDto();
        dto.setId(q.getId());
        dto.setSessionId(q.getSession().getId());
        dto.setQuestionText(q.getQuestionText());
        dto.setQuestionType(q.getQuestionType().toString());
        dto.setSequenceNumber(q.getSequenceNumber());
        dto.setGeneratedBy(q.getGeneratedBy().toString());
        dto.setIsFollowUp(q.getIsFollowUp());
        dto.setContext(q.getContext());
        dto.setExpectedAnswer(q.getExpectedAnswer());
        dto.setDifficultyLevel(q.getDifficultyLevel());
        dto.setAskedAt(q.getAskedAt());
        dto.setResponseDeadline(q.getResponseDeadline());
        dto.setAnswerTimeoutSeconds(q.getAnswerTimeoutSeconds());
        return dto;
    }

    private InterviewAnswerDto mapAnswerToDto(InterviewAnswer a) {
        InterviewAnswerDto dto = new InterviewAnswerDto();
        dto.setId(a.getId());
        dto.setQuestionId(a.getQuestion().getId());
        dto.setAnswerText(a.getAnswerText());
        dto.setTranscript(a.getTranscript());
        dto.setRecordingUrl(a.getRecordingUrl());
        dto.setAnsweredAt(a.getAnsweredAt());
        dto.setDurationSeconds(a.getDurationSeconds());
        dto.setWordCount(a.getWordCount());
        dto.setSentimentScore(a.getSentimentScore());
        dto.setConfidenceScore(a.getConfidenceScore());
        dto.setClarityScore(a.getClarityScore());
        dto.setRelevanceScore(a.getRelevanceScore());
        dto.setCompletenessScore(a.getCompletenessScore());
        dto.setEvaluationFeedback(a.getEvaluationFeedback());
        dto.setImprovementSuggestions(a.getImprovementSuggestions());
        return dto;
    }

    private InterviewEvaluationDto mapEvaluationToDto(InterviewEvaluation e) {
        InterviewEvaluationDto dto = new InterviewEvaluationDto();
        dto.setId(e.getId());
        dto.setSessionId(e.getSession().getId());
        dto.setTechnicalSkillsScore(e.getTechnicalSkillsScore());
        dto.setTechnicalFeedback(e.getTechnicalFeedback());
        dto.setDomainKnowledgeScore(e.getDomainKnowledgeScore());
        dto.setDomainKnowledgeFeedback(e.getDomainKnowledgeFeedback());
        dto.setCommunicationScore(e.getCommunicationScore());
        dto.setCommunicationFeedback(e.getCommunicationFeedback());
        dto.setConfidenceScore(e.getConfidenceScore());
        dto.setConfidenceFeedback(e.getConfidenceFeedback());
        dto.setProblemSolvingScore(e.getProblemSolvingScore());
        dto.setProblemSolvingFeedback(e.getProblemSolvingFeedback());
        dto.setOverallRating(e.getOverallRating());
        dto.setFinalRecommendation(e.getFinalRecommendation() != null
                ? e.getFinalRecommendation().toString() : "MAYBE");
        // Include interviewPassStatus in DTO
        dto.setInterviewPassStatus(e.getInterviewPassStatus());
        // Include pass status in DTO so frontend can show PASSED / UNDER_REVIEW / FAILED

        dto.setStrengths(e.getStrengths());
        dto.setWeaknesses(e.getWeaknesses());
        dto.setDevelopmentAreas(e.getDevelopmentAreas());
        dto.setNextSteps(e.getNextSteps());
        dto.setTotalQuestionsAsked(e.getTotalQuestionsAsked());
        dto.setTotalQuestionsAnswered(e.getTotalQuestionsAnswered());
        dto.setAverageAnswerDuration(e.getAverageAnswerDuration());
        dto.setAverageResponseTime(e.getAverageResponseTime());
        dto.setCompletionPercentage(e.getCompletionPercentage());
        dto.setPercentileRank(e.getPercentileRank());
        dto.setEvaluatedAt(e.getEvaluatedAt());
        return dto;
    }

    // ── Null helpers ──────────────────────────────────────────────────────

    private double nvl(Double v, double def) { return v != null ? v : def; }
    private double toDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }
}