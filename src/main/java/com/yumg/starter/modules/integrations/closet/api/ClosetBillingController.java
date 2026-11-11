package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.modules.integrations.closet.api.dto.ClosetBillingRequests.*;
import com.yumg.starter.modules.integrations.closet.application.ClosetBillingService;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetRequestSigner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet")
public class ClosetBillingController {
    private final ClosetBillingService service;
    public ClosetBillingController(ClosetBillingService service) { this.service = service; }

    @GetMapping("/billing/settings")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode settings() {
        return service.read("/billing/settings", Map.of());
    }

    @PutMapping("/billing/settings")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public JsonNode settingsChange(@Valid @RequestBody SettingsChange body) {
        return service.write("SETTINGS_CHANGED", "PUT", "/billing/settings", body, null);
    }

    @GetMapping("/billing/tiers")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode tiers() {
        return service.read("/billing/tiers", Map.of());
    }

    @PutMapping("/billing/tiers/{tier}")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public JsonNode tierChange(@PathVariable @Pattern(regexp = "^[a-z]+\\.[a-z]+$") String tier, @Valid @RequestBody TierChange body) {
        return service.write("TIER_CHANGED", "PUT", "/billing/tiers/" + ClosetRequestSigner.encodeComponent(tier), body, null);
    }

    @GetMapping("/billing/cost-rates")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode costRates(@RequestParam(required = false) @Size(max = 80) String model) {
        return service.read("/billing/cost-rates", query("model", model));
    }

    @PostMapping("/billing/cost-rates")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public ResponseEntity<JsonNode> costCreate(@Valid @RequestBody CostRateCreate body) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(service.write("COST_CREATED", "POST", "/billing/cost-rates", body, null));
    }

    @GetMapping("/billing/margin")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode margin(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.read("/billing/margin", query("from", from, "to", to));
    }

    @GetMapping("/billing/margin/alerts")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode alerts() {
        return service.read("/billing/margin/alerts", Map.of());
    }

    @GetMapping("/billing/margin/explain")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode explain(@RequestParam @Pattern(regexp = "^[a-z]+\\.[a-z]+$") String tier, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return service.read("/billing/margin/explain", query("tier", tier, "from", from, "to", to, "limit", limit));
    }

    @GetMapping("/billing/products")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode products() {
        return service.read("/billing/products", Map.of());
    }

    @PostMapping("/billing/products")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public ResponseEntity<JsonNode> productCreate(@Valid @RequestBody ProductCreate body) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(service.write("PRODUCT_CREATED", "POST", "/billing/products", body, null));
    }

    @PutMapping("/billing/products/{id}")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public JsonNode productChange(@PathVariable UUID id, @Valid @RequestBody ProductUpdate body) {
        return service.write("PRODUCT_CHANGED", "PUT", "/billing/products/" + id, body, null);
    }

    @GetMapping("/billing/orders")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode orders(@RequestParam(required = false) @Pattern(regexp = "^(CREATED|PAID|FULFILLED|CLOSED|FAILED|DISPUTED)$") String status, @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return service.read("/billing/orders", query("status", status, "limit", limit));
    }

    @PostMapping("/billing/orders/{id}/sandbox-dispute")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public JsonNode dispute(@PathVariable UUID id) {
        return service.write("SANDBOX_DISPUTE", "POST", "/billing/orders/" + id + "/sandbox-dispute", null, null);
    }

    @GetMapping("/billing/redemption-batches")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode batches() {
        return service.read("/billing/redemption-batches", Map.of());
    }

    @PostMapping("/billing/redemption-batches")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public ResponseEntity<JsonNode> batchCreate(@Valid @RequestBody BatchCreate body) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(service.write("BATCH_CREATED", "POST", "/billing/redemption-batches", body, null));
    }

    @GetMapping("/billing/redemption-batches/{id}/codes")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode codes(@PathVariable UUID id) {
        return service.read("/billing/redemption-batches/" + id + "/codes", Map.of());
    }

    @PostMapping("/billing/redemption-batches/{id}/disable")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public JsonNode disable(@PathVariable UUID id) {
        return service.write("BATCH_DISABLED", "POST", "/billing/redemption-batches/" + id + "/disable", null, null);
    }

    @DeleteMapping("/billing/entitlements/{id}")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public JsonNode revoke(@PathVariable UUID id) {
        return service.write("ENTITLEMENT_REVOKED", "DELETE", "/billing/entitlements/" + id, null, null);
    }

    @GetMapping("/users/{username}/billing")
    @PreAuthorize("hasAuthority('closet:billing:read')")
    public JsonNode userBilling(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_.-]{2,80}$") String username) {
        return service.read("/users/" + ClosetRequestSigner.encodeComponent(username) + "/billing", Map.of());
    }

    @PostMapping("/users/{username}/points")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public JsonNode points(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_.-]{2,80}$") String username, @Valid @RequestBody PointsChange body, @RequestHeader(value = "Idempotency-Key", required = false) @NotBlank @Size(max = 100) String key) {
        return service.write("POINTS_CHANGED", "POST", "/users/" + ClosetRequestSigner.encodeComponent(username) + "/points", body, key);
    }

    @PostMapping("/users/{username}/entitlements")
    @PreAuthorize("hasAuthority('closet:billing:write')")
    public ResponseEntity<JsonNode> grant(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_.-]{2,80}$") String username, @Valid @RequestBody EntitlementCreate body) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store").body(service.write("ENTITLEMENT_GRANTED", "POST", "/users/" + ClosetRequestSigner.encodeComponent(username) + "/entitlements", body, null));
    }

    private static Map<String, List<String>> query(Object... fields) {
        var result = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < fields.length; i += 2) if (fields[i + 1] != null)
            result.put((String) fields[i], List.of(fields[i + 1].toString()));
        return result;
    }
}
