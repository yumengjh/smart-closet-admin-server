package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.common.api.PageResponse;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetCreateInviteRequest;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetRegistrationRequest;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetUpdateInviteRequest;
import com.yumg.starter.modules.integrations.closet.application.ClosetManagementService;
import com.yumg.starter.modules.integrations.closet.application.ClosetRegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet")
public class ClosetRegistrationController {
    private final ClosetRegistrationService registration;
    private final ClosetManagementService management;
    public ClosetRegistrationController(ClosetRegistrationService registration, ClosetManagementService management) {
        this.registration = registration; this.management = management;
    }
    @GetMapping("/registration") @PreAuthorize("hasAuthority('closet:registration:read')")
    public JsonNode registration() { return registration.registration(); }
    @PutMapping("/registration") @PreAuthorize("hasAuthority('closet:registration:write')")
    public JsonNode registration(@Valid @RequestBody ClosetRegistrationRequest request) { return registration.registration(request); }
    @GetMapping("/invites") @PreAuthorize("hasAuthority('closet:registration:read')")
    public PageResponse<JsonNode> invites(@RequestParam MultiValueMap<String, String> query) { return management.page("/invites", query); }
    @PostMapping("/invites") @PreAuthorize("hasAuthority('closet:registration:write')")
    public ResponseEntity<JsonNode> create(@Valid @RequestBody ClosetCreateInviteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registration.create(request));
    }
    @GetMapping("/invites/{id}") @PreAuthorize("hasAuthority('closet:registration:read')")
    public JsonNode invite(@PathVariable @NotBlank @Size(max = 36) String id) { return registration.invite(id); }
    @PatchMapping("/invites/{id}") @PreAuthorize("hasAuthority('closet:registration:write')")
    public JsonNode update(@PathVariable @NotBlank @Size(max = 36) String id,
                           @Valid @RequestBody ClosetUpdateInviteRequest request) { return registration.update(id, request); }
    @DeleteMapping("/invites/{id}") @PreAuthorize("hasAuthority('closet:registration:write')")
    public ResponseEntity<Void> revoke(@PathVariable @NotBlank @Size(max = 36) String id) {
        registration.revoke(id); return ResponseEntity.noContent().build();
    }
}
