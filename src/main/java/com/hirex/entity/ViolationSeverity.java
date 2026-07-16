package com.hirex.entity;

/**
 * ViolationSeverity — NEW: AI Interview Monitoring (Proctoring)
 *
 *  LOW      – minor / likely benign (e.g. a single brief noise spike).
 *  MEDIUM   – noteworthy (e.g. camera/mic off, momentary face absence, tab switch).
 *  HIGH     – strong integrity concern (e.g. multiple faces, prolonged face absence).
 *  CRITICAL – almost certainly a violation (e.g. phone detected in frame).
 */
public enum ViolationSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
