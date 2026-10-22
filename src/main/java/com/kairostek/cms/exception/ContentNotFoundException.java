package com.kairostek.cms.exception;

public class ContentNotFoundException extends RuntimeException {

    public ContentNotFoundException(Long id) {
        super("Content with id " + id + " not found");
    }
}
