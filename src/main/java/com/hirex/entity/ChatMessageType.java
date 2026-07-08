package com.hirex.entity;

/**
 * Type discriminator for ChatMessage.
 *
 *  TEXT            – normal text message typed by a user.
 *  FILE             – a file/image attachment.
 *  INTERVIEW_LINK   – auto-generated system message containing a secure
 *                      live-interview meeting link. Rendered by the frontend
 *                      as a "Join Interview" button instead of plain text.
 */
public enum ChatMessageType {
    TEXT,
    FILE,
    INTERVIEW_LINK
}
