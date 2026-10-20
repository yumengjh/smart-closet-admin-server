package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.common.api.PageResponse;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetTemplateReviewRequest;
import com.yumg.starter.modules.integrations.closet.application.ClosetManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet")
public class ClosetManagementController {
    private final ClosetManagementService management;
    public ClosetManagementController(ClosetManagementService management) { this.management = management; }

    @GetMapping("/account-events") @PreAuthorize("hasAuthority('closet:user:read')")
    public PageResponse<JsonNode> accountEvents(@RequestParam MultiValueMap<String, String> query) { return management.page("/account-events", query); }

    @GetMapping("/ops/status") @PreAuthorize("hasAuthority('closet:ops:read')")
    public JsonNode status(@RequestParam MultiValueMap<String, String> query) { return management.get("/ops/status", query); }
    @GetMapping("/ops/tasks") @PreAuthorize("hasAuthority('closet:ops:read')")
    public PageResponse<JsonNode> tasks(@RequestParam MultiValueMap<String, String> query) { return management.page("/ops/tasks", query); }
    @GetMapping("/ops/operation-log") @PreAuthorize("hasAuthority('closet:ops:read')")
    public PageResponse<JsonNode> log(@RequestParam MultiValueMap<String, String> query) { return management.page("/ops/operation-log", query); }
    @GetMapping("/dashboard/snapshot") @PreAuthorize("hasAuthority('closet:dashboard:read')")
    public JsonNode snapshot(@RequestParam MultiValueMap<String, String> query) { return management.get("/dashboard/snapshot", query); }
    @GetMapping("/exports/events") @PreAuthorize("hasAuthority('closet:exports:read')")
    public JsonNode events(@RequestParam MultiValueMap<String, String> query) { return management.get("/exports/events", query); }
    @GetMapping("/exports/daily") @PreAuthorize("hasAuthority('closet:exports:read')")
    public JsonNode daily(@RequestParam MultiValueMap<String, String> query) {
        if (!query.getOrDefault("format", List.of("json")).equals(List.of("json"))) throw ApiException.invalidParameter();
        return management.get("/exports/daily", query);
    }
    @GetMapping(value = "/exports/daily", params = "format=csv") @PreAuthorize("hasAuthority('closet:exports:read')")
    public ResponseEntity<StreamingResponseBody> dailyCsv(@RequestParam MultiValueMap<String, String> query) {
        if (!query.get("format").equals(List.of("csv"))) throw ApiException.invalidParameter();
        byte[] bytes = management.dailyCsv(query);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"closet-daily.csv\"")
                .body(output -> output.write(bytes));
    }
    @GetMapping("/templates") @PreAuthorize("hasAuthority('closet:templates:read')")
    public JsonNode templates(@RequestParam MultiValueMap<String, String> query) { return management.get("/templates", query); }
    @PatchMapping("/templates/{id}") @PreAuthorize("hasAuthority('closet:templates:write')")
    public JsonNode review(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_.-]{1,80}$") String id,
                           @Valid @RequestBody ClosetTemplateReviewRequest request) { return management.review(id, request); }
}
