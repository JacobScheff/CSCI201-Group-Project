package com.rally;

public class ApiException extends RuntimeException {
    final int status;
    final String code;
    public ApiException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public static ApiException bad(String message) { return new ApiException(400,"INVALID_INPUT",message); }
    public static ApiException unauthorized() { return new ApiException(401,"UNAUTHORIZED","Please sign in again."); }
    public static ApiException missing() { return new ApiException(404,"NOT_FOUND","That item could not be found."); }
}
