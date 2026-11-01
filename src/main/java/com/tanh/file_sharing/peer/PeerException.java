package com.tanh.file_sharing.peer;

public class PeerException extends RuntimeException {
    private final String code;

    public PeerException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PeerException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
