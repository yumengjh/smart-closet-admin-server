package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.modules.integrations.closet.api.dto.ClosetTryOnRequests.*;
import com.yumg.starter.modules.integrations.closet.application.ClosetTryOnService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet/tryon")
public class ClosetTryOnController {
    private final ClosetTryOnService service;
    public ClosetTryOnController(ClosetTryOnService service) { this.service = service; }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('closet:tryon:read')")
    public JsonNode settings(@RequestParam MultiValueMap<String, String> query) {
        return service.read("/settings", query);
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('closet:tryon:write')")
    public JsonNode updateSettings(@Valid @RequestBody SettingsChange request) {
        return service.write("UPDATE_SETTINGS", "PUT", "/settings", request, "settings");
    }

    @PostMapping("/settings/reset")
    @PreAuthorize("hasAuthority('closet:tryon:write')")
    public JsonNode resetSettings(@Valid @RequestBody Reset request) {
        return service.write("RESET_SETTINGS", "POST", "/settings/reset", request, "settings");
    }

    @GetMapping("/users/{username}/policy")
    @PreAuthorize("hasAuthority('closet:tryon:read')")
    public JsonNode userPolicy(@PathVariable @Pattern(regexp = com.yumg.starter.modules.integrations.closet.application.ClosetUsernames.EXISTING) String username, @RequestParam MultiValueMap<String, String> query) {
        return service.read("/users/" + ClosetTryOnService.encode(username) + "/policy", query);
    }

    @PutMapping("/users/{username}/policy")
    @PreAuthorize("hasAuthority('closet:tryon:write')")
    public JsonNode updateUserPolicy(@PathVariable @Pattern(regexp = com.yumg.starter.modules.integrations.closet.application.ClosetUsernames.EXISTING) String username, @Valid @RequestBody UserPolicy request) {
        return service.write("UPDATE_USER_POLICY", "PUT", "/users/" + ClosetTryOnService.encode(username) + "/policy", request, username);
    }

    @GetMapping("/users/{username}/usage")
    @PreAuthorize("hasAuthority('closet:tryon:read')")
    public JsonNode userUsage(@PathVariable @Pattern(regexp = com.yumg.starter.modules.integrations.closet.application.ClosetUsernames.EXISTING) String username, @RequestParam MultiValueMap<String, String> query) {
        return service.read("/users/" + ClosetTryOnService.encode(username) + "/usage", query);
    }

    @PostMapping("/users/{username}/startup-failures/reset")
    @PreAuthorize("hasAuthority('closet:tryon:write')")
    public JsonNode resetStartupFailures(@PathVariable @Pattern(regexp = com.yumg.starter.modules.integrations.closet.application.ClosetUsernames.EXISTING) String username) {
        return service.write("RESET_STARTUP_FAILURES", "POST", "/users/" + ClosetTryOnService.encode(username) + "/startup-failures/reset", null, username);
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('closet:tryon:read')")
    public JsonNode accounts(@RequestParam MultiValueMap<String, String> query) {
        return service.read("/accounts", query);
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAuthority('closet:tryon:credentials')")
    public JsonNode registerAccount(@Valid @RequestBody CredentialInput request) {
        return service.write("REGISTER_ACCOUNT", "POST", "/accounts", request, "accounts");
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('closet:tryon:read')")
    public JsonNode account(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> query) {
        return service.read("/accounts/" + id, query);
    }

    @PatchMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('closet:tryon:write')")
    public JsonNode updateAccount(@PathVariable UUID id, @Valid @RequestBody AccountChange request) {
        return service.write("UPDATE_ACCOUNT", "PATCH", "/accounts/" + id, request, id.toString());
    }

    @PostMapping("/accounts/{id}/credentials")
    @PreAuthorize("hasAuthority('closet:tryon:credentials')")
    public JsonNode rotateCredentials(@PathVariable UUID id, @Valid @RequestBody CredentialInput request) {
        return service.write("ROTATE_CREDENTIALS", "POST", "/accounts/" + id + "/credentials", request, id.toString());
    }

    @DeleteMapping("/accounts/{id}/credentials/{credentialId}")
    @PreAuthorize("hasAuthority('closet:tryon:credentials')")
    public JsonNode removeCredential(@PathVariable UUID id, @PathVariable UUID credentialId) {
        return service.write("REMOVE_CREDENTIAL", "DELETE", "/accounts/" + id + "/credentials/" + credentialId, null, id.toString());
    }

    @PostMapping("/accounts/{id}/probe")
    @PreAuthorize("hasAuthority('closet:tryon:probe')")
    public JsonNode probeAccount(@PathVariable UUID id) {
        return service.write("PROBE_ACCOUNT", "POST", "/accounts/" + id + "/probe", null, id.toString());
    }

    @PostMapping("/accounts/{id}/budget")
    @PreAuthorize("hasAuthority('closet:tryon:budget')")
    public JsonNode updateBudget(@PathVariable UUID id, @Valid @RequestBody BudgetChange request) {
        return service.write("UPDATE_BUDGET", "POST", "/accounts/" + id + "/budget", request, id.toString());
    }

    @GetMapping("/pool")
    @PreAuthorize("hasAuthority('closet:tryon:read')")
    public JsonNode pool(@RequestParam MultiValueMap<String, String> query) {
        return service.read("/pool", query);
    }

    @GetMapping("/reference-source")
    @PreAuthorize("hasAuthority('closet:tryon:read')")
    public JsonNode referenceSource(@RequestParam MultiValueMap<String, String> query) {
        return service.read("/reference-source", query);
    }

    @GetMapping("/usage")
    @PreAuthorize("hasAuthority('closet:tryon:read')")
    public JsonNode usage(@RequestParam MultiValueMap<String, String> query) {
        return service.read("/usage", query);
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAuthority('closet:tryon:read')")
    public JsonNode sessions(@RequestParam MultiValueMap<String, String> query) {
        return service.read("/sessions", query);
    }

    @GetMapping("/sessions/{id}")
    @PreAuthorize("hasAuthority('closet:tryon:read')")
    public JsonNode session(@PathVariable UUID id, @RequestParam MultiValueMap<String, String> query) {
        return service.read("/sessions/" + id, query);
    }

    @PostMapping("/sessions/{id}/stop")
    @PreAuthorize("hasAuthority('closet:tryon:stop')")
    public JsonNode stopSession(@PathVariable UUID id) {
        return service.write("STOP_SESSION", "POST", "/sessions/" + id + "/stop", null, id.toString());
    }

    @PostMapping("/accounts/{id}/stop-sessions")
    @PreAuthorize("hasAuthority('closet:tryon:stop')")
    public JsonNode stopAccountSessions(@PathVariable UUID id) {
        return service.write("STOP_ACCOUNT_SESSIONS", "POST", "/accounts/" + id + "/stop-sessions", null, id.toString());
    }
}
