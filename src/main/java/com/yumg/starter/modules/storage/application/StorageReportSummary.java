package com.yumg.starter.modules.storage.application;

record StorageReportSummary(long totalObjects, long totalBytes,
                            long userReferencedObjects, long candidateReferencedObjects,
                            long orphanObjects, long missingFileObjects) {}
