package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.modules.integrations.closet.application.ClosetModelsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/closet/models")
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetModelsController {
    private final ClosetModelsService models;
    public ClosetModelsController(ClosetModelsService models) { this.models = models; }

    @GetMapping @PreAuthorize("hasAuthority('closet:models:read')")
    public JsonNode overview() { return models.overview(); }

    @GetMapping("/usage") @PreAuthorize("hasAuthority('closet:models:read')")
    public JsonNode usage(@RequestParam MultiValueMap<String, String> query) { return models.usage(query); }

    @GetMapping({"/tiers", "/resend", "/health/events", "/health/timeline", "/traces", "/probe", "/balances", "/alerts", "/alerts/settings"})
    @PreAuthorize("hasAuthority('closet:models:read')")
    public JsonNode redundancyRead(jakarta.servlet.http.HttpServletRequest request, @RequestParam MultiValueMap<String, String> query) {
        return models.redundancyRead(request.getRequestURI().substring(request.getContextPath().length() + "/api/v1/closet/models".length()), query);
    }
    @GetMapping("/traces/{id}") @PreAuthorize("hasAuthority('closet:models:read')")
    public JsonNode trace(@PathVariable String id) { return models.trace(id); }
    @GetMapping("/providers/{id}/balance/history") @PreAuthorize("hasAuthority('closet:models:read')")
    public JsonNode balanceHistory(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) { return models.balanceHistory(id, query); }
    @PutMapping("/pools/{pool}") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode putPool(@PathVariable String pool, @RequestBody JsonNode body) { return models.putPool(pool, body); }
    @DeleteMapping("/pools/{pool}") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode deletePool(@PathVariable String pool) { return models.deletePool(pool); }
    @PutMapping({"/tiers", "/resend", "/probe", "/alerts/settings"}) @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode redundancySettings(jakarta.servlet.http.HttpServletRequest request, @RequestBody JsonNode body) {
        return models.redundancySettings(request.getRequestURI().substring(request.getContextPath().length() + "/api/v1/closet/models".length()), body);
    }
    @PostMapping("/entries/{id}/drain") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode drain(@PathVariable String id) { return models.entryAction(id, "drain"); }
    @PostMapping("/entries/{id}/undrain") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode undrain(@PathVariable String id) { return models.entryAction(id, "undrain"); }
    @PostMapping("/entries/{id}/probe-paid") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode paidProbe(@PathVariable String id) { return models.entryAction(id, "probe-paid"); }
    @PutMapping("/providers/{id}/billing") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode billing(@PathVariable String id, @RequestBody JsonNode body) { return models.billing(id, body); }
    @PostMapping("/providers/{id}/balance/refresh") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode refreshBalance(@PathVariable String id) { return models.balanceAction(id, "refresh", null); }
    @PostMapping("/providers/{id}/balance/manual") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode manualBalance(@PathVariable String id, @RequestBody JsonNode body) { return models.balanceAction(id, "manual", body); }
    @PostMapping("/alerts/test") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode testAlert() { return models.testAlert(); }

    @PostMapping("/providers") @PreAuthorize("hasAuthority('closet:models:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode createProvider(@RequestBody JsonNode body) { return models.createProvider(body); }
    @PatchMapping("/providers/{id}") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode updateProvider(@PathVariable String id, @RequestBody JsonNode body) { return models.updateProvider(id, body); }
    @DeleteMapping("/providers/{id}") @PreAuthorize("hasAuthority('closet:models:write')")
    public ResponseEntity<Void> deleteProvider(@PathVariable String id) { models.deleteProvider(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/providers/{id}/probe") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode probe(@PathVariable String id) { return models.probe(id); }

    @PostMapping("/entries") @PreAuthorize("hasAuthority('closet:models:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode createEntry(@RequestBody JsonNode body) { return models.createEntry(body); }
    @PatchMapping("/entries/{id}") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode updateEntry(@PathVariable String id, @RequestBody JsonNode body) { return models.updateEntry(id, body); }
    @PutMapping("/entries/{id}/references") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode setReferences(@PathVariable String id, @RequestBody JsonNode body) { return models.setReferences(id, body); }
    @PutMapping("/entries/{id}/timeout") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode setTimeout(@PathVariable String id, @RequestBody JsonNode body) { return models.setTimeout(id, body); }
    @DeleteMapping("/entries/{id}") @PreAuthorize("hasAuthority('closet:models:write')")
    public ResponseEntity<Void> deleteEntry(@PathVariable String id) { models.deleteEntry(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/entries/{id}/resume") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode resume(@PathVariable String id) { return models.resume(id); }
    @PostMapping("/entries/{id}/test") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode test(@PathVariable String id) { return models.test(id); }

    @PutMapping("/routing/{capability}") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode route(@PathVariable String capability, @RequestBody JsonNode body) { return models.route(capability, body); }
    @DeleteMapping("/routing/{capability}") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode resetRoute(@PathVariable String capability) { return models.resetRoute(capability); }
}
