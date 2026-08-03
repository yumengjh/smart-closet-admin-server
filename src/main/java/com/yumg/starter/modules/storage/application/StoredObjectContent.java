package com.yumg.starter.modules.storage.application;

public record StoredObjectContent(String contentType, long sizeBytes, String sha256, byte[] bytes) {
    public StoredObjectContent {
        bytes = bytes.clone();
    }

    @Override public byte[] bytes() { return bytes.clone(); }
}
