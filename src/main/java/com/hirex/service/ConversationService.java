package com.hirex.service;

import com.hirex.entity.Application;
import com.hirex.entity.ChatMessage;
import com.hirex.entity.ChatMessageType;
import com.hirex.entity.User;
import com.hirex.repository.ChatMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requirement #2 — Automatic Conversation Creation.
 *
 * BUG FIX: This class previously had no @Service annotation and an empty
 * method body. Since RecruiterApplicantService, AtsBulkService, and
 * JobAtsService all take a ConversationService as a constructor argument,
 * Spring had no bean to inject there -- the application context failed to
 * start at all ("No qualifying bean of type ConversationService"). It is
 * now a proper singleton service.
 *
 * A "conversation" in HireX is not a separate table -- it is simply the
 * set of ChatMessages tied to an Application (see ChatMessage.application).
 * The conversation becomes visible in:
 *   - ChatService.getConversationsForManager / GroupedByJob
 *   - ChatService.getConversationsForJobseeker
 * the moment the Application's status enters the chat-eligible statuses
 * (SHORTLISTED and beyond) -- that part already worked correctly and needs
 * no separate Conversation entity.
 *
 * This service's job is to seed that thread with a single, friendly
 * welcome message from the recruiter the first time a candidate becomes
 * SHORTLISTED, so the conversation the recruiter/candidate open isn't
 * empty. It is safe to call multiple times (from ATS bulk runs, single
 * ATS analysis, or a manual recruiter shortlist) -- it will never create
 * more than one welcome message per application (idempotent).
 */
@Service
public class ConversationService {

    private final ChatMessageRepository chatRepo;

    public ConversationService(ChatMessageRepository chatRepo) {
        this.chatRepo = chatRepo;
    }

    @Transactional
    public void ensureConversationForShortlisted(Application application) {
        if (application == null || application.getId() == null) return;

        // Idempotent: skip if this application's conversation already has
        // at least one message (welcome message or otherwise).
        if (chatRepo.existsByApplication(application)) {
            return;
        }

        User applicant = application.getApplicant();
        User manager = (application.getJob() != null && application.getJob().getCompany() != null)
                ? application.getJob().getCompany().getManager()
                : null;

        // No recruiter to send the welcome message "from" -- the conversation
        // will still show up in both panels (driven purely by application
        // status), just without an opening message.
        if (manager == null || applicant == null) return;

        String jobTitle = application.getJob() != null ? application.getJob().getTitle() : "the role";
        Integer score = application.getAtsScore();

        StringBuilder content = new StringBuilder();
        content.append("Hi ").append(applicant.getName()).append("! ")
               .append("Congratulations - you have been shortlisted for the \"")
               .append(jobTitle).append("\" position");
        if (score != null) {
            content.append(" with an ATS score of ").append(score).append("/100");
        }
        content.append(". We're excited to move forward - feel free to ask any questions ")
               .append("about the role, interview process, or next steps.");

        ChatMessage welcome = new ChatMessage();
        welcome.setApplication(application);
        welcome.setSender(manager);
        welcome.setType(ChatMessageType.TEXT);
        welcome.setContent(content.toString());
        chatRepo.save(welcome);
    }
}
