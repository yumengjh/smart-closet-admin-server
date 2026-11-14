package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.modules.integrations.closet.application.ClosetCreationService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Explicit creation routes; no catch-all proxy is exposed. */
@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet/creations")
public class ClosetCreationController {
    private final ClosetCreationService service;
    public ClosetCreationController(ClosetCreationService service) { this.service = service; }

    @GetMapping("/templates") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode templates() { return service.read("/templates", Map.of()); }

    @PostMapping("/templates/{id}/versions") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode create(@PathVariable String id, @RequestBody JsonNode body) {
        return service.write("VERSION_CREATED", "POST", template(id) + "/versions", body);
    }

    @GetMapping("/templates/{id}/versions/{versionId}") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode version(@PathVariable String id, @PathVariable String versionId) {
        return service.read(versionPath(id, versionId), Map.of());
    }

    @PutMapping("/templates/{id}/versions/{versionId}") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode update(@PathVariable String id, @PathVariable String versionId, @RequestBody JsonNode body) {
        return service.write("VERSION_UPDATED", "PUT", versionPath(id, versionId), body);
    }

    @PostMapping("/templates/{id}/versions/{versionId}/verify") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode verify(@PathVariable String id, @PathVariable String versionId, @RequestBody JsonNode body) {
        return service.write("VERSION_VERIFIED", "POST", versionPath(id, versionId) + "/verify", body);
    }

    @PostMapping("/templates/{id}/versions/{versionId}/publish") @PreAuthorize("hasAuthority('closet:creation:publish')")
    public JsonNode publish(@PathVariable String id, @PathVariable String versionId, @RequestBody JsonNode body) {
        return service.write("VERSION_PUBLISHED", "POST", versionPath(id, versionId) + "/publish", body);
    }

    @PostMapping("/templates/{id}/retire") @PreAuthorize("hasAuthority('closet:creation:publish')")
    public JsonNode retire(@PathVariable String id, @RequestBody JsonNode body) {
        return service.write("TEMPLATE_RETIRED", "POST", template(id) + "/retire", body);
    }

    @GetMapping("/capabilities") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode capabilities() { return service.read("/capabilities", Map.of()); }

    @PutMapping("/capabilities") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode capabilities(@RequestBody JsonNode body) { return service.write("CAPABILITY_UPDATED", "PUT", "/capabilities", body); }

    @GetMapping("/usage") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode usage(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 30)
            throw com.yumg.starter.common.api.ApiException.invalidParameter();
        return service.read("/usage", Map.of("from", List.of(from.toString()), "to", List.of(to.toString())));
    }

    private static String template(String id) {
        if (!List.of("sunlit-flatlay", "dual-view-editorial", "daily-outfit-share").contains(id))
            throw com.yumg.starter.common.api.ApiException.invalidParameter();
        return "/templates/" + id;
    }
    private static String versionPath(String id, String versionId) {
        if (!versionId.matches("[A-Za-z0-9_-]{1,80}"))
            throw com.yumg.starter.common.api.ApiException.invalidParameter();
        return template(id) + "/versions/" + versionId;
    }
}
