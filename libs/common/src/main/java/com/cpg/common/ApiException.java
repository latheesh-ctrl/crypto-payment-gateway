package com.cpg.common;

/**
 * A failure that maps to a client-visible error. {@code code} is a stable, documented machine-readable
 * string (for example {@code ledger_unbalanced}); {@code status} is the HTTP status to return.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "resource_missing", what + " not found");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }
}
