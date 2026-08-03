package com.yumg.starter.modules.storage.infrastructure;

public record StorageHealthResult(boolean up, String message, long usableBytes, long totalBytes) {}
