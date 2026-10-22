package com.kairostek.cms.exception;

public class DuplicateContentKeyException extends RuntimeException {

    public DuplicateContentKeyException(String key, String language) {
        super("Content with key '" + key + "' already exists for language '" + language + "'");
    }
}
