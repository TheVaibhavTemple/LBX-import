package com.bofa.ibox.lockbox.exception;

import com.bofa.ibox.lockbox.model.ErrorCode;

/**
 * Thrown when a lockbox file fails any spec-defined validation rule.
 * Carries the error code from the spec (e.g. EF-101, EV-215).
 */
public class LockboxValidationException extends RuntimeException {

    private final ErrorCode errorCode;

    public LockboxValidationException(ErrorCode errorCode, String message) {
        super("[" + errorCode.getCode() + "] " + errorCode.getDescription() + " – " + message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
