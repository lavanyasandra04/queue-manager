package com.queuemanager;

/** Thrown for any expected error; becomes {"error": "..."} with the given HTTP status. */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
