package com.yumg.starter.modules.storage.api.dto;

import java.time.Instant;

public record StorageReportResponse(
        Instant scannedAt,
        long totalObjects,
        long totalBytes,
        long userReferencedObjects,
        long candidateReferencedObjects,
        long orphanObjects,
        long missingFileObjects,
        long untrackedFiles,
        long temporaryFiles,
        long usableBytes,
        long capacityBytes) {}
