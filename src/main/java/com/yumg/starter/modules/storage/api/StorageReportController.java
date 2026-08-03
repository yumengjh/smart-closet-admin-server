package com.yumg.starter.modules.storage.api;

import com.yumg.starter.modules.storage.api.dto.StorageReportResponse;
import com.yumg.starter.modules.storage.application.StorageReportService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/storage")
public class StorageReportController {
    private final StorageReportService storage;
    public StorageReportController(StorageReportService storage) { this.storage = storage; }

    @GetMapping("/report")
    @PreAuthorize("hasAuthority('system:gc:read')")
    public StorageReportResponse report() { return storage.report(); }
}
