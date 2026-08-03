package com.yumg.starter.modules.runtimeconfig.application;

import java.time.Instant;

public record RuntimeCacheStatus(
        String code,
        String displayName,
        long snapshotRevision,
        long databaseRevision,
        boolean synchronizedState,
        Instant loadedAt,
        Instant lastCheckedAt,
        Instant lastReloadedAt,
        Instant lastFailedAt,
        String lastFailureMessage,
        int entryCount) {}
