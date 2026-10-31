package com.saburi.travel.util;

public class ApiException extends RuntimeException {
    public final int status;
    public ApiException(int status, String message) { super(message); this.status = status; }
}
