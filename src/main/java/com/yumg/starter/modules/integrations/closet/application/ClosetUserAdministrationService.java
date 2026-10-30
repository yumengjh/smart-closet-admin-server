package com.yumg.starter.modules.integrations.closet.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.common.api.PageResponse;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;

@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetUserAdministrationService {
    private final ClosetManagementClient client;
    private final AuditService audit;
    private final ObjectMapper mapper;
    public ClosetUserAdministrationService(ClosetManagementClient client, AuditService audit, ObjectMapper mapper) {
        this.client = client; this.audit = audit; this.mapper = mapper;
    }

    public PageResponse<JsonNode> list(String q, String status, String role, String sort, String order, String registeredVia, String deactivated, int page, int size) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        add(query, "q", q); add(query, "status", status); add(query, "role", role);
        add(query, "sort", sort); add(query, "order", order);
        add(query, "registeredVia", registeredVia); add(query, "deactivated", deactivated);
        query.put("page", List.of(Integer.toString(page + 1))); query.put("size", List.of(Integer.toString(size)));
        JsonNode result = client.get("/users", query);
        List<JsonNode> items = result.path("items").isArray() ? mapper.convertValue(result.path("items"),
                mapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class)) : List.of();
        long total = result.path("total").asLong(); int upstreamPage = result.path("page").asInt(page + 1);
        int responseSize = result.path("size").asInt(size);
        return new PageResponse<>(items, Math.max(0, upstreamPage - 1), responseSize, total,
                responseSize <= 0 ? 0 : (int) ((total + responseSize - 1) / responseSize));
    }
    public JsonNode detail(String username) { return client.get("/users/" + segment(username), Map.of()); }
    public JsonNode sessions(String username) { return client.get("/users/" + segment(username) + "/sessions", Map.of()); }
    public JsonNode portableLinks(String username) { return client.get("/users/" + segment(username) + "/portable-links", Map.of()); }
    public JsonNode quota(String username) { return client.get("/users/" + segment(username) + "/quota", Map.of()); }

    public JsonNode loginLock(String username) { return client.get("/users/" + segment(username) + "/login-lock", Map.of()); }
    public JsonNode events(String username) { return client.get("/users/" + segment(username) + "/events", Map.of()); }
    public JsonNode profile(String username, Object body) {
        return audited("CLOSET_USER_PROFILE_UPDATED", username, Map.of("username", username),
                () -> client.patch("/users/" + segment(username) + "/profile", body));
    }
    public void revokeSession(String username, String sessionRef) {
        audited("CLOSET_USER_SESSION_REVOKED", username, Map.of("username", username, "sessionRef", sessionRef), () -> {
            if (sessionRef == null || !sessionRef.matches("^[0-9a-f]{16}$")) throw ApiException.invalidParameter();
            client.delete("/users/" + segment(username) + "/sessions/" + UriUtils.encodePathSegment(sessionRef, StandardCharsets.UTF_8));
            return null;
        });
    }
    public JsonNode unlock(String username) {
        return audited("CLOSET_USER_LOGIN_UNLOCKED", username, Map.of("username", username),
                () -> client.deleteWithResponse("/users/" + segment(username) + "/login-lock"));
    }

    public JsonNode create(Object body, String username) {
        return audited("CLOSET_USER_CREATED", username, Map.of("username", username), () -> client.post("/users", body));
    }
    public JsonNode status(String username, boolean enabled) {
        return audited(enabled ? "CLOSET_USER_ENABLED" : "CLOSET_USER_DISABLED", username,
                Map.of("username", username, "enabled", enabled), () -> client.patch("/users/" + segment(username) + "/status", Map.of("enabled", enabled)));
    }
    public JsonNode role(String username, String role) {
        return audited("CLOSET_USER_ROLE_CHANGED", username, Map.of("username", username, "role", role),
                () -> client.patch("/users/" + segment(username) + "/role", Map.of("role", role)));
    }
    public JsonNode resetPassword(String username, Object body) {
        return audited("CLOSET_USER_PASSWORD_RESET", username, Map.of("username", username),
                () -> client.post("/users/" + segment(username) + "/password-reset", body));
    }
    public JsonNode revokeSessions(String username) {
        return audited("CLOSET_USER_SESSIONS_REVOKED", username, Map.of("username", username),
                () -> client.deleteWithResponse("/users/" + segment(username) + "/sessions"));
    }
    public void revokePortableLink(String username, String linkId) {
        audited("CLOSET_PORTABLE_LINK_REVOKED", username, Map.of("username", username), () -> {
            if (linkId == null || linkId.isBlank() || linkId.length() > 36) throw ApiException.invalidParameter();
            client.delete("/users/" + segment(username) + "/portable-links/"
                    + UriUtils.encodePathSegment(linkId, StandardCharsets.UTF_8)); return null;
        });
    }
    public JsonNode updateQuota(String username, Object body) {
        return audited("CLOSET_USER_QUOTA_CHANGED", username, Map.of("username", username),
                () -> client.put("/users/" + segment(username) + "/quota", body));
    }

    private <T> T audited(String action, String target, Map<String, Object> metadata, Supplier<T> operation) {
        try {
            T response = operation.get(); audit.event(action, "ClosetUser", target, "SUCCESS", json(metadata)); return response;
        } catch (ApiException exception) {
            audit.event(action, "ClosetUser", target, "FAILURE", json(Map.of("username", target, "code", exception.error().name())));
            throw exception;
        } catch (RuntimeException exception) {
            audit.event(action, "ClosetUser", target, "FAILURE", json(Map.of("username", target, "code", "CLOSET_SERVICE_UNAVAILABLE")));
            throw ApiException.closetServiceUnavailable();
        }
    }
    private String json(Map<String, Object> metadata) {
        try { return mapper.writeValueAsString(metadata); } catch (Exception ignored) { return "{}"; }
    }
    private static void add(Map<String, List<String>> query, String key, String value) {
        if (value != null) query.put(key, List.of(value));
    }
    private static String segment(String value) {
        if (value == null || !value.matches(ClosetUsernames.EXISTING)) throw ApiException.invalidParameter();
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }
}
