package edu.whut.cs.bi.api.oauth;

/**
 * OAuth 协议错误，error 取值遵循 RFC 6749 / RFC 7591 / RFC 8707。
 */
public class OAuthException extends RuntimeException {

    private final String error;
    private final int status;

    public OAuthException(String error, String description) {
        this(error, description, 400);
    }

    public OAuthException(String error, String description, int status) {
        super(description);
        this.error = error;
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public int getStatus() {
        return status;
    }
}
