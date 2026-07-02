package com.hirex.service;

import com.hirex.entity.*;
import com.hirex.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * InterviewEvaluationService
 *
 * Runs the (potentially slow) AI-based interview scoring in the background,
 * completely decoupled from the request that triggered it.
 *
 * WHY THIS EXISTS:
 * Previously, scoring an interview (a per-answer Ollama call for every
 * question PLUS one holistic "grade all 10 answers" Ollama call) ran
 * synchronously inside the same HTTP request/DB transaction as either the
 * final submitAnswer() call or the /complete endpoint call. If Ollama was
 * slow, rate-limited, or unreachable, that request could take 30-60+
 * seconds — long enough to blow past the frontend's request timeout. The
 * candidate would see a generic submission error even though their answer
 * (and the COMPLETED status) had already been saved successfully.
 *
 * Fix: this method is annotated @Async and lives on its own Spring bean.
 * Spring's @Async only works through the proxy, so it MUST be invoked as
 * `evaluationService.evaluateAndFinalizeAsync(...)` from another bean (as
 * AIInterviewService does) — never via `this.` from within the same class,
 * which would silently run synchronously.
 *
 * The session is marked COMPLETED (and the answer is saved) *before* this
 * runs, so the candidate-facing flow finishes immediately regardless of
 * how long AI scoring takes. This method fills in the evaluation and
 * updates the session/application status to PASSED / UNDER_REVIEW / FAILED
 * once it's done.
 */
@Service
public class InterviewEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(InterviewEvaluationService.class);

    private final InterviewSessionRepository    sessionRepository;
    private final InterviewQuestionRepository   questionRepository;
    private final InterviewAnswerRepository     answerRepository;
    private final InterviewEvaluationRepository evaluationRepository;
    private final ApplicationRepository         applicationRepository;
    private final AIQuestionGeneratorService    questionGeneratorService;
    private final AIAnswerEvaluatorService      answerEvaluatorService;

    public InterviewEvaluationService(
            InterviewSessionRepository    sessionRepository,
            InterviewQuestionRepository   questionRepository,
            InterviewAnswerRepository     answerRepository,
            InterviewEvaluationRepository evaluationRepository,
            ApplicationRepository         applicationRepository,
            AIQuestionGeneratorService    questionGeneratorService,
            AIAnswerEvaluatorService      answerEvaluatorService) {
        this.sessionRepository        = sessionRepository;
        this.questionRepository       = questionRepository;
        this.answerRepository         = answerRepository;
        this.evaluationRepository     = evaluationRepository;
        this.applicationRepository    = applicationRepository;
        this.questionGeneratorService = questionGeneratorService;
        this.answerEvaluatorService   = answerEvaluatorService;
    }

    /**
     * Entry point — MUST be called through the Spring proxy (i.e. through an
     * injected InterviewEvaluationService field), never via `this` from
     * inside another method of the same class, or @Async will be ignored.
     * Idempotent: safe to call more than once for the same session.
     */
    @Async("interviewEvaluationExecutor")
    public void evaluateAndFinalizeAsync(Long sessionId) {
        try {
            saveEvaluation(sessionId);
        } catch (Exception e) {
            log.error("Background evaluation failed for session {} ({}). " +
                    "Session remains COMPLETED; evaluation can be retried.",
                    sessionId, e.getMessage(), e);
        }
    }

    @Transactional
    public void saveEvaluation(Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        List<InterviewAnswer> answers =
                answerRepository.findBySessionIdOrderByAnsweredAt(sessionId);

        if (answers.isEmpty()) {
            log.warn("Session {}: no answers found; skipping evaluation.", sessionId);
            return;
        }

        // Idempotency: don't double-score a session
        if (evaluationRepository.findBySessionId(sessionId).isPresent()) {
            log.info("Session {}: evaluation already exists — skipping.", sessionId);
            return;
        }

        InterviewEvaluation evaluation = new InterviewEvaluation(session);

        // ── Attempt Ollama holistic evaluation first ──────────────────
        // Send all 10 Q&As to Ollama for a comprehensive, context-aware evaluation.
        // Falls back to per-answer scores if Ollama is unavailable.
        boolean ollamaEvalApplied = false;
        try {
            Map<String, Object> ollamaResult = requestHolisticEvaluation(session, answers);
            if (ollamaResult != null && !ollamaResult.isEmpty()) {
                evaluation.setOverallRating(        toDouble(ollamaResult.get("overallScore"),     50.0));
                evaluation.setTechnicalSkillsScore( toDouble(ollamaResult.get("technicalScore"),   50.0));
                evaluation.setCommunicationScore(   toDouble(ollamaResult.get("communicationScore"), 50.0));
                evaluation.setConfidenceScore(      toDouble(ollamaResult.get("confidenceScore"),  50.0));
                evaluation.setProblemSolvingScore(  toDouble(ollamaResult.get("problemSolvingScore"), 50.0));
                evaluation.setStrengths(            toStr(ollamaResult.get("strengths"),    ""));
                evaluation.setWeaknesses(           toStr(ollamaResult.get("weaknesses"),   ""));
                evaluation.setDevelopmentAreas(     toStr(ollamaResult.get("developmentAreas"), ""));

                double score = nvl(evaluation.getOverallRating(), 50.0);
                String passStatus;
                if      (score >= 80) passStatus = "PASSED";
                else if (score >= 60) passStatus = "UNDER_REVIEW";
                else                  passStatus = "FAILED";
                evaluation.setInterviewPassStatus(passStatus);

                ollamaEvalApplied = true;
                log.info("Session {}: Ollama holistic evaluation applied. Score={}, Status={}",
                        sessionId, evaluation.getOverallRating(), passStatus);
            }
        } catch (Exception e) {
            log.warn("Session {}: Ollama holistic evaluation failed ({}). Falling back to per-answer scores.",
                    sessionId, e.getMessage());
        }

        // ── Fallback: compute scores from per-answer evaluations ──────
        if (!ollamaEvalApplied) {
            evaluation.setTechnicalSkillsScore(avgRelevance(answers, QuestionType.TECHNICAL));
            evaluation.setDomainKnowledgeScore(avgRelevance(answers, QuestionType.DOMAIN_KNOWLEDGE));
            evaluation.setProblemSolvingScore( avgRelevance(answers, QuestionType.PROBLEM_SOLVING));

            double comm = answers.stream()
                    .mapToDouble(a -> nvl(a.getClarityScore(), 0.5))
                    .average().orElse(0.5) * 100;
            evaluation.setCommunicationScore(comm);

            double conf = answers.stream()
                    .mapToDouble(a -> nvl(a.getConfidenceScore(), 0.5))
                    .average().orElse(0.5) * 100;
            evaluation.setConfidenceScore(conf);

            evaluation.calculateOverallRating();

            evaluation.setStrengths(       answerEvaluatorService.generateStrengths(answers));
            evaluation.setWeaknesses(      answerEvaluatorService.generateWeaknesses(answers));
            evaluation.setDevelopmentAreas(answerEvaluatorService.generateDevelopmentAreas(answers));
        }

        long totalQ       = questionRepository.countBySessionId(sessionId);
        long totalAnswers = answerRepository.countBySessionId(sessionId);
        evaluation.setTotalQuestionsAsked((int) totalQ);
        evaluation.setTotalQuestionsAnswered((int) totalAnswers);
        if (totalQ > 0) {
            evaluation.setCompletionPercentage((double) totalAnswers / totalQ * 100);
        }

        evaluation.setFinalRecommendation(deriveRecommendation(evaluation.getOverallRating()));
        evaluation.setEvaluatedAt(LocalDateTime.now());
        evaluation.setEvaluatedBy("OLLAMA_AI");

        evaluationRepository.save(evaluation);
        log.info("Session {}: evaluation saved. Score={}, Status={}",
                sessionId, evaluation.getOverallRating(), evaluation.getInterviewPassStatus());

        // ── Update session outcome status ─────────────────────────────
        InterviewStatus outcomeStatus = toOutcomeStatus(evaluation.getInterviewPassStatus());
        session.setStatus(outcomeStatus);
        sessionRepository.save(session);
        log.info("Session {} status updated to {}.", sessionId, outcomeStatus);

        // ── Update application status for dashboard visibility ─────────
        try {
            Application app = session.getApplication();
            String passStatus = evaluation.getInterviewPassStatus();
            if ("PASSED".equals(passStatus)) {
                app.setStatus(ApplicationStatus.INTERVIEW_PASSED);
            } else if ("FAILED".equals(passStatus)) {
                app.setStatus(ApplicationStatus.INTERVIEW_FAILED);
            } else {
                app.setStatus(ApplicationStatus.INTERVIEW_COMPLETED); // UNDER_REVIEW or unknown
            }
            applicationRepository.save(app);
            log.info("Application {} status updated to {} after interview.",
                    app.getId(), app.getStatus());
        } catch (Exception e) {
            log.warn("Could not update application status after interview: {}", e.getMessage());
        }
    }

    /**
     * Sends the complete interview (all 10 Q&As) to Ollama for a single
     * comprehensive evaluation. Returns null if Ollama is unavailable or
     * parsing fails — caller falls back to per-answer scores.
     */
    private Map<String, Object> requestHolisticEvaluation(InterviewSession session,
                                                           List<InterviewAnswer> answers) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert technical interviewer. Evaluate the following interview for the position of ")
          .append(session.getPositionTitle()).append(".\n\n");

        sb.append("Interview Transcript:\n");
        for (int i = 0; i < answers.size(); i++) {
            InterviewAnswer a = answers.get(i);
            sb.append("Q").append(i + 1).append(": ").append(a.getQuestion().getQuestionText()).append("\n");
            sb.append("A").append(i + 1).append(": ").append(
                    a.getTranscript() != null ? a.getTranscript() : a.getAnswerText()).append("\n\n");
        }

        sb.append("Provide a JSON evaluation with ONLY these fields (numbers 0-100, strings for text):\n");
        sb.append("{\n");
        sb.append("  \"overallScore\": 75,\n");
        sb.append("  \"technicalScore\": 80,\n");
        sb.append("  \"communicationScore\": 70,\n");
        sb.append("  \"confidenceScore\": 65,\n");
        sb.append("  \"problemSolvingScore\": 78,\n");
        sb.append("  \"strengths\": \"Key strengths here\",\n");
        sb.append("  \"weaknesses\": \"Key weaknesses here\",\n");
        sb.append("  \"developmentAreas\": \"Recommended development areas here\"\n");
        sb.append("}\n");
        sb.append("Return ONLY valid JSON. No markdown, no explanation, no extra text.");

        try {
            String raw = questionGeneratorService.callOllamaRaw(sb.toString());
            if (raw == null || raw.isBlank()) return null;

            raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            int start = raw.indexOf('{');
            int end   = raw.lastIndexOf('}');
            if (start < 0 || end <= start) return null;
            raw = raw.substring(start, end + 1);

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            String inner = raw.replaceAll("[{}]", "");
            for (String line : inner.split("\n")) {
                line = line.trim().replaceAll(",$", "").trim();
                if (!line.contains(":")) continue;
                String[] parts = line.split(":", 2);
                String key   = parts[0].trim().replaceAll("\"", "");
                String value = parts[1].trim().replaceAll("\"", "");
                try {
                    result.put(key, Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    result.put(key, value);
                }
            }
            return result.isEmpty() ? null : result;

        } catch (Exception e) {
            log.warn("Holistic evaluation parse error: {}", e.getMessage());
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

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

    private String toStr(Object v, String def) {
        return v != null && !v.toString().isBlank() ? v.toString() : def;
    }

    private double nvl(Double v, double def) { return v != null ? v : def; }

    private double toDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }
}
