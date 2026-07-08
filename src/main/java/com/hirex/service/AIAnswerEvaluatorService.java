package com.hirex.service;

import com.hirex.entity.*;
import com.hirex.repository.InterviewAnswerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * AIAnswerEvaluatorService — evaluates candidate answers and generates summaries.
 *
 * KEY FIX #1: every OpenAI call is wrapped in try/catch so that an AI failure
 * (quota exhausted, rate-limited, network error) never propagates to the
 * interview session layer.  The interview MUST continue; evaluation is
 * best-effort and falls back to neutral defaults.
 *
 * KEY FIX #2 (performance): per-answer scoring used to run synchronously
 * inside AIInterviewService.submitAnswer(), blocking the candidate-facing
 * POST /answer request on a live Ollama call (up to the 30s RestTemplate
 * read timeout) before the response — and therefore the next question —
 * could appear. evaluateAnswerAsync() below runs that same scoring on the
 * "interviewEvaluationExecutor" background pool (see AsyncConfig), on its
 * own transaction, and writes the scores back once they're ready. The
 * answer row itself is already saved and visible to the candidate before
 * this ever starts, so submitAnswer() now returns almost immediately.
 */
@Service
public class AIAnswerEvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(AIAnswerEvaluatorService.class);

    private final OllamaService ollamaService;
    private final InterviewAnswerRepository answerRepository;

    public AIAnswerEvaluatorService(OllamaService ollamaService,
                                    InterviewAnswerRepository answerRepository) {
        this.ollamaService = ollamaService;
        this.answerRepository = answerRepository;
    }

    // ── Async entry point (background scoring) ─────────────────────────────

    /**
     * Fire-and-forget entry point invoked by AIInterviewService right after
     * an answer is saved. MUST be called through the Spring proxy (i.e.
     * through an injected AIAnswerEvaluatorService field) — never via `this`
     * from within the same class, or @Async is silently ignored and the
     * call runs synchronously again.
     *
     * Takes an answerId (not the entity) because the entity was loaded on
     * the caller's request thread/transaction; reloading it here guarantees
     * we're working with a fresh, attached instance on this thread's own
     * transaction.
     */
    @Async("interviewEvaluationExecutor")
    public void evaluateAnswerAsync(Long answerId) {
        try {
            scoreAndSaveAnswer(answerId);
        } catch (Exception e) {
            log.error("Background evaluation failed for answer {} ({}). " +
                    "Answer remains saved with default scores.", answerId, e.getMessage(), e);
        }
    }

    @Transactional
    public void scoreAndSaveAnswer(Long answerId) {
        InterviewAnswer answer = answerRepository.findById(answerId).orElse(null);
        if (answer == null) {
            log.warn("Answer {} not found; skipping async evaluation.", answerId);
            return;
        }

        Map<String, Object> eval = evaluateAnswer(answer);
        answer.setConfidenceScore(toDouble(eval.get("confidence"),    0.5));
        answer.setClarityScore(   toDouble(eval.get("clarity"),        0.5));
        answer.setRelevanceScore( toDouble(eval.get("relevance"),      0.5));
        answer.setCompletenessScore(toDouble(eval.get("completeness"), 0.5));
        answer.setSentimentScore( toDouble(eval.get("sentiment"),       0.0));
        Object fb = eval.get("feedback");
        answer.setEvaluationFeedback(fb != null ? fb.toString()
                : "Your answer has been recorded.");
        answerRepository.save(answer);
        log.debug("Answer {} evaluated asynchronously.", answerId);
    }

    private double toDouble(Object v, double def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return def; }
    }

    // ── Core evaluation ───────────────────────────────────────────────────

    /**
     * Evaluate a single answer via AI.
     * NEVER throws — returns default scores on any failure so the interview
     * flow is not disrupted by AI unavailability.
     */
    public Map<String, Object> evaluateAnswer(InterviewAnswer answer) {
        InterviewQuestion question = answer.getQuestion();
        String answerText = answer.getTranscript() != null
                ? answer.getTranscript() : answer.getAnswerText();

        if (answerText == null || answerText.isBlank()) {
            log.warn("Empty answer text for question {}; returning default evaluation.",
                    question.getId());
            return getDefaultEvaluation();
        }

        try {
            String prompt = String.format(
                    "Question: %s\n\n" +
                            "Expected context/topic: %s\n\n" +
                            "Candidate answer: %s\n\n" +
                            "Evaluate and return JSON with scores 0.0-1.0:\n" +
                            "{\n" +
                            "  \"relevance\": 0.8,\n" +
                            "  \"completeness\": 0.75,\n" +
                            "  \"clarity\": 0.85,\n" +
                            "  \"confidence\": 0.7,\n" +
                            "  \"depth\": 0.8,\n" +
                            "  \"structure\": 0.7,\n" +
                            "  \"sentiment\": 0.5,\n" +
                            "  \"feedback\": \"Brief feedback here\"\n" +
                            "}",
                    question.getQuestionText(),
                    question.getContext() != null ? question.getContext() : "General assessment",
                    answerText
            );

            String evaluationJson = ollamaService.generateText(prompt);
            Map<String, Object> scores = parseEvaluationResponse(evaluationJson);

            // Merge with defaults so all keys are present even if parsing missed some
            Map<String, Object> result = getDefaultEvaluation();
            result.putAll(scores);
            return result;

        } catch (Exception e) {
            log.warn("AI evaluation failed for question {} ({}). Using default scores.",
                    question.getId(), e.getMessage());
            return getDefaultEvaluation();
        }
    }

    /**
     * Weighted quality score (0–1).  Uses stored scores if AI already ran;
     * falls back to neutral 0.5 if scores are missing.
     */
    public double calculateAnswerQuality(InterviewAnswer answer) {
        double relevance    = nvl(answer.getRelevanceScore(),    0.5);
        double completeness = nvl(answer.getCompletenessScore(), 0.5);
        double clarity      = nvl(answer.getClarityScore(),      0.5);
        double confidence   = nvl(answer.getConfidenceScore(),   0.5);
        return relevance * 0.35 + completeness * 0.30 + clarity * 0.20 + confidence * 0.15;
    }

    // ── Summary generation ────────────────────────────────────────────────

    /**
     * Generate a "strengths" paragraph from all answers.
     * Returns a placeholder string when AI is unavailable — NEVER throws.
     */
    public String generateStrengths(List<InterviewAnswer> answers) {
        StringBuilder prompt = new StringBuilder(
                "Analyze these interview answers and identify candidate strengths:\n\n");
        answers.stream()
                .filter(a -> a.getRelevanceScore() != null && a.getRelevanceScore() > 0.7)
                .forEach(a -> prompt.append("Strong answer: ").append(a.getTranscript()).append("\n"));

        prompt.append("\nSummarize the candidate's key strengths in 3-4 bullet points.");

        try {
            String result = ollamaService.generateText(prompt.toString());
            if (result == null || result.isBlank()) return fallbackStrengths();
            return result;
        } catch (Exception e) {
            log.warn("Strengths generation failed: {}", e.getMessage());
            return fallbackStrengths();
        }
    }

    /**
     * Generate a "weaknesses / areas for improvement" paragraph.
     * Returns a placeholder when AI is unavailable — NEVER throws.
     */
    public String generateWeaknesses(List<InterviewAnswer> answers) {
        StringBuilder prompt = new StringBuilder(
                "Analyze these interview answers and identify areas for improvement:\n\n");
        answers.stream()
                .filter(a -> a.getRelevanceScore() != null && a.getRelevanceScore() < 0.5)
                .forEach(a -> prompt.append("Answer needing improvement: ")
                        .append(a.getTranscript()).append("\n"));

        prompt.append("\nSummarize the candidate's main areas for improvement in 3-4 bullet points.");

        try {
            String result = ollamaService.generateText(prompt.toString());
            if (result == null || result.isBlank()) return fallbackWeaknesses();
            return result;
        } catch (Exception e) {
            log.warn("Weaknesses generation failed: {}", e.getMessage());
            return fallbackWeaknesses();
        }
    }

    /**
     * Generate "development areas" recommendations.
     * Returns a placeholder when AI is unavailable — NEVER throws.
     */
    public String generateDevelopmentAreas(List<InterviewAnswer> answers) {
        StringBuilder prompt = new StringBuilder(
                "Based on these interview answers, identify skills and areas to develop:\n\n");
        answers.forEach(a -> prompt.append("Q&A: ")
                .append(a.getQuestion().getQuestionText()).append("\n")
                .append("Answer: ").append(a.getTranscript()).append("\n\n"));

        prompt.append("Provide 3-4 specific development recommendations for professional growth.");

        try {
            String result = ollamaService.generateText(prompt.toString());
            if (result == null || result.isBlank()) return fallbackDevelopmentAreas();
            return result;
        } catch (Exception e) {
            log.warn("Development areas generation failed: {}", e.getMessage());
            return fallbackDevelopmentAreas();
        }
    }

    // ── Supplementary scoring ─────────────────────────────────────────────

    public Double assessCommunicationSkills(InterviewAnswer answer) {
        String transcript = answer.getTranscript() != null
                ? answer.getTranscript() : answer.getAnswerText();
        if (transcript == null || transcript.isBlank()) return 0.5;

        double score = 0.5;
        if (transcript.contains("clearly") || transcript.contains("specifically") ||
                transcript.contains("for example")) score += 0.1;

        int sentences = transcript.split("[.!?]+").length;
        if (sentences > 2 && sentences < 20) score += 0.1;

        int fillerCount = countOccurrences(transcript, "um")
                + countOccurrences(transcript, "uh")
                + countOccurrences(transcript, "like")
                + countOccurrences(transcript, "you know");
        if (fillerCount < 2) score += 0.1;

        int wordCount = transcript.split("\\s+").length;
        if (wordCount > 50 && wordCount < 300) score += 0.1;

        return Math.min(score, 1.0);
    }

    public Double assessTechnicalAccuracy(InterviewAnswer answer) {
        String answerText = answer.getTranscript() != null
                ? answer.getTranscript() : answer.getAnswerText();
        String prompt = String.format(
                "Question: %s\n\nAnswer: %s\n\n" +
                        "Rate the technical accuracy 0-1. Return ONLY a number.",
                answer.getQuestion().getQuestionText(), answerText);
        try {
            return Double.parseDouble(ollamaService.generateText(prompt).trim());
        } catch (Exception e) {
            log.warn("Technical accuracy assessment failed: {}", e.getMessage());
            return 0.5;
        }
    }

    public Double assessDepthOfKnowledge(InterviewAnswer answer) {
        String transcript = answer.getTranscript() != null
                ? answer.getTranscript() : answer.getAnswerText();
        String prompt = String.format(
                "Evaluate the depth of understanding in:\n\n%s\n\n" +
                        "Rate 0-1 for technical detail, examples, concepts. Return ONLY a number.",
                transcript);
        try {
            return Double.parseDouble(ollamaService.generateText(prompt).trim());
        } catch (Exception e) {
            log.warn("Depth-of-knowledge assessment failed: {}", e.getMessage());
            return 0.5;
        }
    }

    public Double calculateConsistencyScore(List<InterviewAnswer> answers) {
        if (answers.size() < 2) return 1.0;
        double avg = answers.stream()
                .mapToInt(a -> a.getTranscript() != null
                        ? a.getTranscript().split("\\s+").length : 0)
                .average().orElse(50);
        double totalConsistency = 0;
        for (InterviewAnswer a : answers) {
            int wc = a.getTranscript() != null ? a.getTranscript().split("\\s+").length : 0;
            double deviation = Math.abs(wc - avg) / avg;
            totalConsistency += (1 - Math.min(deviation, 1.0));
        }
        return totalConsistency / answers.size();
    }

    public Double calculateOverallPerformance(List<InterviewAnswer> answers) {
        if (answers.isEmpty()) return 0.0;
        return answers.stream().mapToDouble(this::calculateAnswerQuality).sum() / answers.size();
    }

    public String getRecommendation(Double overallScore) {
        if (overallScore >= 0.85) return "STRONG_YES";
        if (overallScore >= 0.75) return "YES";
        if (overallScore >= 0.60) return "MAYBE";
        if (overallScore >= 0.45) return "NO";
        return "STRONG_NO";
    }

    public String generateInterviewSummary(List<InterviewAnswer> answers, Double overallScore) {
        String recommendation = getRecommendation(overallScore);
        StringBuilder prompt = new StringBuilder(
                "Generate a brief professional summary (2-3 sentences) of this interview:\n\n");
        answers.forEach(a -> prompt.append("Q: ").append(a.getQuestion().getQuestionText())
                .append("\nA: ").append(a.getTranscript()).append("\n\n"));
        prompt.append(String.format("Overall score: %.1f/100\nRecommendation: %s\n",
                overallScore * 100, recommendation));

        try {
            return ollamaService.generateText(prompt.toString());
        } catch (Exception e) {
            log.warn("Interview summary failed: {}", e.getMessage());
            return String.format(
                    "The interview has been completed. Overall performance score: %.0f/100. " +
                            "Recommendation: %s.", overallScore * 100, recommendation);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Map<String, Object> parseEvaluationResponse(String json) {
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isBlank()) return result;
        try {
            // Strip markdown fences if present
            json = json.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            // Find the JSON object
            int start = json.indexOf('{');
            int end   = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            json = json.replaceAll("[{}\"\\[\\]]", "");
            for (String pair : json.split(",")) {
                if (!pair.contains(":")) continue;
                String[] kv  = pair.split(":", 2);
                String key   = kv[0].trim();
                String value = kv[1].trim();
                try {
                    result.put(key, Double.parseDouble(value));
                } catch (NumberFormatException e) {
                    result.put(key, value);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse evaluation JSON: {}", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> getDefaultEvaluation() {
        Map<String, Object> d = new HashMap<>();
        d.put("relevance",    0.5);
        d.put("completeness", 0.5);
        d.put("clarity",      0.5);
        d.put("confidence",   0.5);
        d.put("sentiment",    0.0);
        d.put("feedback",
                "AI evaluation is temporarily unavailable. Your answer has been recorded.");
        return d;
    }

    private String fallbackStrengths() {
        return "AI analysis temporarily unavailable. " +
                "The candidate's strengths will be assessed once the service is restored.";
    }

    private String fallbackWeaknesses() {
        return "AI analysis temporarily unavailable. " +
                "Areas for improvement will be assessed once the service is restored.";
    }

    private String fallbackDevelopmentAreas() {
        return "AI analysis temporarily unavailable. " +
                "Development recommendations will be generated once the service is restored.";
    }

    private double nvl(Double value, double defaultValue) {
        return value != null ? value : defaultValue;
    }

    private int countOccurrences(String text, String word) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(word, idx)) != -1) { count++; idx += word.length(); }
        return count;
    }
}