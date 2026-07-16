package com.hirex.entity;

/**
 * ViolationType — NEW: AI Interview Monitoring (Proctoring)
 *
 * The category of a real-time proctoring violation detected client-side
 * during a live interview (candidate side) and reported to the backend
 * over the /app/live/violation STOMP destination.
 *
 *  PHONE_DETECTED         – a mobile phone was detected in the candidate's video frame.
 *  NO_FACE_DETECTED       – no face detected in frame for a sustained period.
 *  MULTIPLE_FACES_DETECTED– more than one face detected in frame (possible assistance).
 *  FACE_ABSENCE_PROLONGED – face was absent for longer than the "prolonged" threshold
 *                            (escalates NO_FACE_DETECTED to a higher severity).
 *  NOISE_DETECTED         – background noise/voice above the configured threshold
 *                            was detected on the candidate's microphone.
 *  TAB_SWITCH              – candidate navigated away from the interview tab/window
 *                            (visibility change / blur).
 *  CAMERA_OFF              – candidate's camera was turned off or disconnected mid-interview.
 *  MICROPHONE_OFF          – candidate's microphone was muted or disconnected mid-interview.
 */
public enum ViolationType {
    PHONE_DETECTED,
    NO_FACE_DETECTED,
    MULTIPLE_FACES_DETECTED,
    FACE_ABSENCE_PROLONGED,
    NOISE_DETECTED,
    TAB_SWITCH,
    CAMERA_OFF,
    MICROPHONE_OFF
}
