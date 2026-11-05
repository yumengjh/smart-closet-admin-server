package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetRequestSigner;
import com.yumg.starter.modules.security.application.AuditService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetTryOnService {
    private final ClosetManagementClient client;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ClosetTryOnService(ClosetManagementClient client, AuditService audit, ObjectMapper mapper) {
        this.client = client;
        this.audit = audit;
        this.mapper = mapper;
    }

    public static String encode(String username) { return ClosetRequestSigner.encodeComponent(username); }

    public JsonNode read(String path, Map<String, List<String>> query) {
        if (!query.isEmpty()) throw ApiException.invalidParameter();
        return normalize(path, client.get("/tryon" + path, Map.of()));
    }

    /** Audit outcome and target only: credential input and all provider responses stay out of platform audit metadata. */
    public JsonNode write(String action, String method, String path, Object body, String target) {
        try {
            JsonNode result = switch (method) {
                case "PUT" -> client.put("/tryon" + path, body);
                case "PATCH" -> client.patch("/tryon" + path, body);
                case "DELETE" -> client.deleteWithResponse("/tryon" + path);
                case "POST" -> client.post("/tryon" + path, body);
                default -> throw new IllegalArgumentException("Unsupported try-on operation");
            };
            audit.event("CLOSET_TRYON_" + action, "ClosetTryOn", target, "SUCCESS", null);
            return normalize(path, result);
        } catch (ApiException exception) {
            audit.event("CLOSET_TRYON_" + action, "ClosetTryOn", target, "FAILURE",
                    mapper.writeValueAsString(Map.of("code", exception.error().name())));
            throw exception;
        }
    }

    /** H2 returns JDBC column labels in uppercase; PostgreSQL uses lowercase. Keep the admin API stable. */
    private JsonNode normalize(String path, JsonNode result) {
        if (result == null) return null;
        if (path.equals("/usage") && result.isArray()) {
            for (JsonNode row : result) {
                lowerSqlKeys(row);
                String day = row.path("quota_day").asText();
                if (row instanceof ObjectNode object && !day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    try { object.put("quota_day", Instant.parse(day).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()); }
                    catch (DateTimeParseException ignored) { /* Preserve unknown source formats instead of inventing a date. */ }
                }
            }
        } else if (result.isArray()) {
            for (JsonNode account : result) normalizeAccount(account);
        } else {
            normalizeAccount(result);
            if (result.path("accounts").isArray()) for (JsonNode account : result.path("accounts")) normalizeAccount(account);
        }
        return result;
    }

    private static void normalizeAccount(JsonNode account) {
        if (account.path("credentials").isArray()) for (JsonNode credential : account.path("credentials")) lowerSqlKeys(credential);
    }

    private static void lowerSqlKeys(JsonNode row) {
        if (!(row instanceof ObjectNode object)) return;
        var names = new ArrayList<String>();
        object.propertyNames().forEach(names::add);
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!name.equals(lower)) object.set(lower, object.remove(name));
        }
    }

}
