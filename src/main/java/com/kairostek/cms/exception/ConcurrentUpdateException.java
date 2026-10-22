package com.kairostek.cms.exception;

public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(Long id) {
        super("Content with id " + id + " was modified concurrently. Refresh and retry with the latest version.");
    }
}
