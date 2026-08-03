package com.yumg.starter.modules.storage.infrastructure;

public record StoredFile(String objectKey, long sizeBytes, String sha256) {}
