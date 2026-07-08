package com.hirex.entity;

/**
 * Where a LiveInterviewSession came from.
 *
 *  AI_INTERVIEW – auto-created by AIInterviewService the moment the candidate
 *                 starts their AI interview (Live Broadcasting feature).
 *  MANUAL       – explicitly created by a recruiter via the "Live Camera
 *                 Monitoring" picker UI (pre-existing feature).
 */
public enum LiveSessionOrigin {
    AI_INTERVIEW,
    MANUAL
}
