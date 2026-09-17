package com.pegasus.pegasustcgapi.common;

/**
 * Envelope wrapped around every response, success or failure, so clients can read
 * {@code success} before looking at anything else.
 *
 * @param <T> payload type; {@code Void} for endpoints that only report an outcome.
 */
public class ApiResult<T> {

    private boolean success;
    private String message;
    private T data;

    public ApiResult() {
    }

    public ApiResult(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(true, "Success", data);
    }

    public static <T> ApiResult<T> success(String message, T data) {
        return new ApiResult<>(true, message, data);
    }

    public static <T> ApiResult<T> error(String message) {
        return new ApiResult<>(false, message, null);
    }

    /**
     * Failure carrying machine-readable detail — the error code and any field
     * violations — so the envelope does not throw away what the handler knows.
     */
    public static <T> ApiResult<T> error(String message, T data) {
        return new ApiResult<>(false, message, data);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
