package com.hirex.controller;


import com.hirex.dto.*;
import com.hirex.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;


@RestController
@RequestMapping("/api/interview")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AIInterviewController {

    private static final Logger log = LoggerFactory.getLogger(AIInterviewController.class);

    // GLUE FIX: total questions per interview — mirrors AIInterviewService.TOTAL_QUESTIONS,
    // used only to report progress on the live broadcast (question N of TOTAL_BROADCAST_QUESTIONS).
    private static final int TOTAL_BROADCAST_QUESTIONS = 10;

    private final AIInterviewService aiInterviewService;
    private final AIQuestionGeneratorService questionGeneratorService;
    private final AIAnswerEvaluatorService answerEvaluatorService;
    private final OllamaService ollamaService;
    private final LiveInterviewService liveInterviewService;

    public AIInterviewController(
            AIInterviewService aiInterviewService,
            AIQuestionGeneratorService questionGeneratorService,
            AIAnswerEvaluatorService answerEvaluatorService,
            OllamaService ollamaService,
            LiveInterviewService liveInterviewService) {
        this.aiInterviewService = aiInterviewService;
        this.questionGeneratorService = questionGeneratorService;
        this.answerEvaluatorService = answerEvaluatorService;
        this.ollamaService = ollamaService;
        this.liveInterviewService = liveInterviewService;
    }

    // FIXED: Schedule interview for an application
    @PostMapping("/schedule/{applicationId}")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<?> scheduleInterview(
            @PathVariable Long applicationId,
            @RequestParam(defaultValue = "MEDIUM") String template) {
        try {
            InterviewSessionDto session = aiInterviewService.scheduleInterview(applicationId, template);
            return ResponseEntity.status(HttpStatus.CREATED).body(session);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse("Failed to schedule interview: " + e.getMessage()));
        }
    }

    // FIXED: Start interview (join room)
    @PostMapping("/{sessionId}/start")
    @PreAuthorize("hasRole('JOBSEEKER')")
    public ResponseEntity<?> startInterview(
            @PathVariable Long sessionId,
            Principal principal) {
        try {
            InterviewSessionDto session = aiInterviewService.startInterview(sessionId, principal.getName());

            // GLUE: auto-start the live broadcast for the recruiter to watch.
            // Wrapped so a broadcast failure never blocks the interview itself.
            try {
                liveInterviewService.startBroadcastForAIInterview(sessionId);
            } catch (Exception broadcastEx) {
                log.warn("Could not start live broadcast for interview {}: {}",
                        sessionId, broadcastEx.getMessage());
            }

            return ResponseEntity.ok(
                    new HashMap<String, Object>() {{
                        put("message", "Interview started successfully");
                        put("session", session);
                    }}
            );
        } catch (RuntimeException e) {
            if (e.getMessage().contains("Unauthorized")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                        new ErrorResponse("Cannot start this interview: " + e.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse("Cannot start interview: " + e.getMessage()));
        }
    }

    // FIXED: Get interview by application ID
    @GetMapping("/application/{applicationId}")
    public ResponseEntity<?> getApplicationInterview(@PathVariable Long applicationId) {
        try {
            InterviewSessionDto session = aiInterviewService.getApplicationInterview(applicationId);
            return ResponseEntity.ok(session);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse("No interview session found for this application. Ask your recruiter to schedule the interview first."));
        }
    }

    // FIXED: Get current interview status
    @GetMapping("/{sessionId}")
    public ResponseEntity<?> getInterview(
            @PathVariable Long sessionId,
            Principal principal) {
        try {
            InterviewSessionDto session = aiInterviewService.getInterviewSummary(sessionId);
            return ResponseEntity.ok(session);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse("Interview not found"));
        }
    }

    // Get next question
    @GetMapping("/{sessionId}/next-question")
    public ResponseEntity<?> getNextQuestion(@PathVariable Long sessionId) {
        try {
            InterviewQuestionDto question = aiInterviewService.getNextQuestion(sessionId);

            // GLUE: mirror the current question onto the live broadcast so the
            // recruiter's dashboard updates in real time. Never fatal.
            try {
                if (question != null) {
                    int questionNumber = question.getSequenceNumber() != null
                            ? question.getSequenceNumber() : 0;
                    liveInterviewService.updateCurrentQuestion(
                            sessionId, questionNumber, question.getQuestionText(),
                            TOTAL_BROADCAST_QUESTIONS);
                }
            } catch (Exception broadcastEx) {
                log.warn("Could not push question update to live broadcast for interview {}: {}",
                        sessionId, broadcastEx.getMessage());
            }

            return ResponseEntity.ok(question);
        } catch (RuntimeException e) {
            // BUG FIX: this used to check e.getMessage().contains("completed") — a
            // case-sensitive match. But the message that's actually thrown when the
            // final answer has already auto-completed the session is
            // "...status: COMPLETED)." (uppercase, from the enum's toString()), so
            // the lowercase check silently failed and this fell through to a
            // generic 400 instead of the intended 410 GONE. Fixed by matching
            // case-insensitively and covering every terminal status, not just
            // COMPLETED (an evaluated session may already be PASSED/FAILED/
            // UNDER_REVIEW by the time the frontend asks for the "next" question).
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            boolean interviewIsOver =
                    msg.contains("completed") || msg.contains("passed") ||
                            msg.contains("failed") || msg.contains("under_review") ||
                            msg.contains("no more questions");
            if (interviewIsOver) {
                return ResponseEntity.status(HttpStatus.GONE).body(
                        new ErrorResponse("Interview has been completed"));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse(e.getMessage()));
        }
    }

    // Submit answer
    @PostMapping("/{sessionId}/answer")
    public ResponseEntity<?> submitAnswer(
            @PathVariable Long sessionId,
            @RequestBody AnswerSubmission submission) {
        try {
            InterviewAnswerDto answer = aiInterviewService.submitAnswer(
                    sessionId,
                    submission.getQuestionId(),
                    submission.getAnswerText(),
                    submission.getTranscript());
            return ResponseEntity.ok(answer);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse("Failed to submit answer: " + e.getMessage()));
        }
    }

    // Generate follow-up question
    @PostMapping("/{sessionId}/follow-up")
    public ResponseEntity<?> generateFollowUp(
            @PathVariable Long sessionId,
            @RequestBody FollowUpRequest request) {
        try {
            InterviewQuestionDto followUp = aiInterviewService.generateFollowUp(
                    sessionId,
                    request.getQuestionId(),
                    request.getContext());
            return ResponseEntity.ok(followUp);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse("Failed to generate follow-up: " + e.getMessage()));
        }
    }

    // Complete interview
    // Idempotent — safe to call more than once (e.g. auto-complete already ran
    // after the last answer, and the frontend also calls this explicitly).
    // Returns a specific, meaningful error message instead of a generic one.
    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<?> completeInterview(
            @PathVariable Long sessionId,
            Principal principal) {
        try {
            InterviewSessionDto session = aiInterviewService.completeInterview(sessionId);

            // GLUE: auto-stop the live broadcast now that the interview is done.
            try {
                liveInterviewService.endBroadcastForAIInterview(sessionId);
            } catch (Exception broadcastEx) {
                log.warn("Could not end live broadcast for interview {}: {}",
                        sessionId, broadcastEx.getMessage());
            }

            return ResponseEntity.ok(session);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            if (msg.toLowerCase().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse("Interview session not found: " + msg));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse("Failed to submit the interview: " + msg));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ErrorResponse("An unexpected error occurred while submitting your interview. " +
                            "Your answers have been saved — please try again or contact support if this persists."));
        }
    }

    // Get interview report
    @GetMapping("/{sessionId}/report")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<?> getReport(@PathVariable Long sessionId) {
        try {
            InterviewReportDto report = aiInterviewService.getInterviewReport(sessionId);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse("Report not found"));
        }
    }

    // Get all interviews for a job
    @GetMapping("/job/{jobId}/all")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<?> getJobInterviews(@PathVariable Long jobId) {
        try {
            List<InterviewSessionDto> sessions = aiInterviewService.getInterviewsForJob(jobId);
            return ResponseEntity.ok(sessions);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse("Failed to retrieve interviews"));
        }
    }

    // Get interview statistics
    @GetMapping("/job/{jobId}/statistics")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<?> getStatistics(@PathVariable Long jobId) {
        try {
            Map<String, Object> stats = aiInterviewService.getInterviewStatistics(jobId);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse("Failed to retrieve statistics"));
        }
    }

    // Check AI service health
    @GetMapping("/health/ai")
    public ResponseEntity<?> checkAIHealth() {
        boolean healthy = ollamaService.isHealthy();
        if (healthy) {
            return ResponseEntity.ok(new HealthResponse("AI service is operational", true));
        } else {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    new HealthResponse("AI service is unavailable", false));
        }
    }

    // Get AI configuration
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        return ResponseEntity.ok(ollamaService.getUsageStats());
    }

    // Test question generation
    @PostMapping("/test/generate-questions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> testQuestionGeneration(
            @RequestBody QuestionGenerationRequest request) {
        try {
            List<String> questions = ollamaService.generateQuestions(
                    request.getPrompt(),
                    request.getCount());
            return ResponseEntity.ok(new QuestionGenerationResponse(questions));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse("Failed to generate questions: " + e.getMessage()));
        }
    }

    // Test answer evaluation
    @PostMapping("/test/evaluate-answer")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> testAnswerEvaluation(
            @RequestBody AnswerEvaluationRequest request) {
        try {
            Map<String, Double> evaluation = ollamaService.evaluateAnswerQuality(
                    request.getQuestion(),
                    request.getAnswer());
            return ResponseEntity.ok(evaluation);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse("Failed to evaluate answer: " + e.getMessage()));
        }
    }

    // Export interview data
    @GetMapping("/{sessionId}/export")
    @PreAuthorize("hasRole('MANAGER')")
    public ResponseEntity<?> exportInterview(@PathVariable Long sessionId) {
        try {
            InterviewReportDto report = aiInterviewService.getInterviewReport(sessionId);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse("Interview not found"));
        }
    }

    // Inner request/response classes
    public static class AnswerSubmission {
        private Long questionId;
        private String answerText;
        private String transcript;

        public Long getQuestionId() { return questionId; }
        public void setQuestionId(Long questionId) { this.questionId = questionId; }

        public String getAnswerText() { return answerText; }
        public void setAnswerText(String answerText) { this.answerText = answerText; }

        public String getTranscript() { return transcript; }
        public void setTranscript(String transcript) { this.transcript = transcript; }
    }

    public static class FollowUpRequest {
        private Long questionId;
        private String context;

        public Long getQuestionId() { return questionId; }
        public void setQuestionId(Long questionId) { this.questionId = questionId; }

        public String getContext() { return context; }
        public void setContext(String context) { this.context = context; }
    }

    public static class QuestionGenerationRequest {
        private String prompt;
        private int count;

        public String getPrompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
    }

    public static class QuestionGenerationResponse {
        private List<String> questions;
        private int count;

        public QuestionGenerationResponse(List<String> questions) {
            this.questions = questions;
            this.count = questions.size();
        }

        public List<String> getQuestions() { return questions; }
        public int getCount() { return count; }
    }

    public static class AnswerEvaluationRequest {
        private String question;
        private String answer;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }

        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
    }

    public static class ErrorResponse {
        private String message;
        private long timestamp;

        public ErrorResponse(String message) {
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }

        public String getMessage() { return message; }
        public long getTimestamp() { return timestamp; }
    }

    public static class HealthResponse {
        private String message;
        private boolean healthy;

        public HealthResponse(String message, boolean healthy) {
            this.message = message;
            this.healthy = healthy;
        }

        public String getMessage() { return message; }
        public boolean isHealthy() { return healthy; }
    }
}