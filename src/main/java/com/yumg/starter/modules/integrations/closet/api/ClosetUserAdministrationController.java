package com.yumg.starter.modules.integrations.closet.api;

import tools.jackson.databind.JsonNode;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetProfileRequest;
import com.yumg.starter.common.api.PageResponse;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetCreateUserRequest;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetPasswordResetRequest;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetQuotaRequest;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetRoleRequest;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetStatusRequest;
import com.yumg.starter.modules.integrations.closet.application.ClosetUserAdministrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet/users")
public class ClosetUserAdministrationController {
    private static final String USERNAME = "^[A-Za-z0-9_.-]{2,80}$";
    private final ClosetUserAdministrationService users;
    public ClosetUserAdministrationController(ClosetUserAdministrationService users) { this.users = users; }

    @GetMapping @PreAuthorize("hasAuthority('closet:user:read')")
    public PageResponse<JsonNode> list(@RequestParam(required = false) @Size(max = 80) String q,
            @RequestParam(required = false) @Pattern(regexp = "^(ENABLED|DISABLED)$") String status,
            @RequestParam(required = false) @Pattern(regexp = "^(USER|ADMIN)$") String role,
            @RequestParam(required = false) @Pattern(regexp = "^(createdAt|username|lastActiveAt|lastLoginAt)$") String sort,
            @RequestParam(required = false) @Pattern(regexp = "^(asc|desc)$") String order,
            @RequestParam(required = false) @Pattern(regexp = "^(ADMIN|INVITE|OPEN)$") String registeredVia,
            @RequestParam(required = false) @Pattern(regexp = "^(true|false)$") String deactivated,
            @RequestParam(defaultValue = "0") @Min(0) @Max(2147483646) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, @RequestParam MultiValueMap<String, String> query) {
        Set<String> allowed = Set.of("q", "status", "role", "sort", "order", "registeredVia", "deactivated", "page", "size");
        if (query.entrySet().stream().anyMatch(entry -> !allowed.contains(entry.getKey()) || entry.getValue().size() != 1))
            throw ApiException.invalidParameter();
        return users.list(q, status, role, sort, order, registeredVia, deactivated, page, size);
    }
    @GetMapping("/{username}") @PreAuthorize("hasAuthority('closet:user:read')")
    public JsonNode detail(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.detail(username); }
    @PostMapping @PreAuthorize("hasAuthority('closet:user:write')")
    public ResponseEntity<JsonNode> create(@Valid @RequestBody ClosetCreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(users.create(request, request.username()));
    }
    @PatchMapping("/{username}/status") @PreAuthorize("hasAuthority('closet:user:write')")
    public JsonNode status(@PathVariable @Pattern(regexp = USERNAME) String username,
                           @Valid @RequestBody ClosetStatusRequest request) {
        return users.status(username, request.enabled());
    }
    @PatchMapping("/{username}/role") @PreAuthorize("hasAuthority('closet:user:write')")
    public JsonNode role(@PathVariable @Pattern(regexp = USERNAME) String username,
                         @Valid @RequestBody ClosetRoleRequest request) {
        return users.role(username, request.role());
    }
    @PostMapping("/{username}/password-reset") @PreAuthorize("hasAuthority('closet:user:reset-password')")
    public JsonNode resetPassword(@PathVariable @Pattern(regexp = USERNAME) String username,
                                  @Valid @RequestBody ClosetPasswordResetRequest request) {
        return users.resetPassword(username, request);
    }
    @GetMapping("/{username}/sessions") @PreAuthorize("hasAuthority('closet:user:read')")
    public JsonNode sessions(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.sessions(username); }
    @DeleteMapping("/{username}/sessions") @PreAuthorize("hasAuthority('closet:user:write')")
    public JsonNode revokeSessions(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.revokeSessions(username); }
    @PatchMapping("/{username}/profile") @PreAuthorize("hasAuthority('closet:user:write')")
    public JsonNode profile(@PathVariable @Pattern(regexp = USERNAME) String username,
                           @Valid @RequestBody ClosetProfileRequest request) {
        return users.profile(username, request);
    }
    @DeleteMapping("/{username}/sessions/{sessionRef}") @PreAuthorize("hasAuthority('closet:user:write')")
    public ResponseEntity<Void> revokeSession(@PathVariable @Pattern(regexp = USERNAME) String username,
            @PathVariable @Pattern(regexp = "^[0-9a-f]{16}$") String sessionRef) {
        users.revokeSession(username, sessionRef); return ResponseEntity.noContent().build();
    }
    @GetMapping("/{username}/login-lock") @PreAuthorize("hasAuthority('closet:user:read')")
    public JsonNode loginLock(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.loginLock(username); }
    @DeleteMapping("/{username}/login-lock") @PreAuthorize("hasAuthority('closet:user:write')")
    public JsonNode unlock(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.unlock(username); }
    @GetMapping("/{username}/events") @PreAuthorize("hasAuthority('closet:user:read')")
    public JsonNode events(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.events(username); }
    @GetMapping("/{username}/portable-links") @PreAuthorize("hasAuthority('closet:user:read')")
    public JsonNode portableLinks(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.portableLinks(username); }
    @DeleteMapping("/{username}/portable-links/{linkId}") @PreAuthorize("hasAuthority('closet:user:write')")
    public ResponseEntity<Void> revokePortableLink(@PathVariable @Pattern(regexp = USERNAME) String username,
            @PathVariable @Size(max = 36) String linkId) {
        users.revokePortableLink(username, linkId); return ResponseEntity.noContent().build();
    }
    @GetMapping("/{username}/quota") @PreAuthorize("hasAuthority('closet:user:read')")
    public JsonNode quota(@PathVariable @Pattern(regexp = USERNAME) String username) { return users.quota(username); }
    @PutMapping("/{username}/quota") @PreAuthorize("hasAuthority('closet:quota:write')")
    public JsonNode quota(@PathVariable @Pattern(regexp = USERNAME) String username,
                          @Valid @RequestBody ClosetQuotaRequest request) {
        return users.updateQuota(username, request);
    }
}
