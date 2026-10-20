package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetCreateInviteRequest;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetRegistrationRequest;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetUpdateInviteRequest;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.security.application.AuditService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetRegistrationService {
    private final ClosetManagementClient client;
    private final AuditService audit;
    private final ObjectMapper mapper;
    public ClosetRegistrationService(ClosetManagementClient client, AuditService audit, ObjectMapper mapper) {
        this.client = client; this.audit = audit; this.mapper = mapper;
    }
    public JsonNode registration() { return client.get("/registration", Map.of()); }
    public JsonNode registration(ClosetRegistrationRequest request) {
        return audited("CLOSET_REGISTRATION_MODE_CHANGED", "ClosetRegistration", "registration",
                () -> client.put("/registration", request));
    }
    public JsonNode invite(String id) { return client.get("/invites/" + segment(id), Map.of()); }
    public JsonNode create(ClosetCreateInviteRequest request) {
        return audited("CLOSET_INVITE_CREATED", "ClosetInvite", null, () -> client.post("/invites", request));
    }
    public JsonNode update(String id, ClosetUpdateInviteRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request.note() != null) body.put("note", request.note());
        if (request.maxUses() != null) body.put("maxUses", request.maxUses());
        if (request.expiresAtMs() != null) body.put("expiresAtMs", request.expiresAtMs());
        return audited("CLOSET_INVITE_UPDATED", "ClosetInvite", id, () -> client.patch("/invites/" + segment(id), body));
    }
    public void revoke(String id) {
        audited("CLOSET_INVITE_REVOKED", "ClosetInvite", id, () -> { client.delete("/invites/" + segment(id)); return null; });
    }

    private JsonNode audited(String action, String type, String target, Supplier<JsonNode> operation) {
        try {
            JsonNode result = operation.get();
            String id = target == null ? result.path("id").asText() : target;
            audit.event(action, type, id, "SUCCESS", mapper.writeValueAsString(Map.of("id", id)));
            return result;
        } catch (ApiException exception) {
            failure(action, type, target, exception.error().name());
            throw exception;
        } catch (RuntimeException exception) {
            failure(action, type, target, "CLOSET_SERVICE_UNAVAILABLE");
            throw ApiException.closetServiceUnavailable();
        }
    }
    private void failure(String action, String type, String target, String errorCode) {
        audit.event(action, type, target, "FAILURE", mapper.writeValueAsString(Map.of(
                "id", target == null ? "" : target, "errorCode", errorCode)));
    }
    private static String segment(String id) {
        if (id == null || id.isBlank() || id.length() > 36) throw ApiException.invalidParameter();
        return UriUtils.encodePathSegment(id, StandardCharsets.UTF_8);
    }
}
