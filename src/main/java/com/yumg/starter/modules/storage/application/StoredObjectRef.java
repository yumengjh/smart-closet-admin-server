package com.yumg.starter.modules.storage.application;

public record StoredObjectRef(String id, String contentType, long sizeBytes, String sha256) {}
