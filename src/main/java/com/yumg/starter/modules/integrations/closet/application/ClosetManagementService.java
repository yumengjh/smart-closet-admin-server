package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.common.api.PageResponse;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetTemplateReviewRequest;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.security.application.AuditService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetManagementService {
    private static final Map<String, Set<String>> QUERIES = Map.ofEntries(
            Map.entry("/invites", Set.of("status", "page", "size")),
            Map.entry("/account-events", Set.of("from", "to", "username", "action", "actor", "page", "size")),
            Map.entry("/ops/status", Set.of()),
            Map.entry("/ops/assistant-token-budget", Set.of()),
            Map.entry("/security-audit", Set.of("from", "to", "username", "category", "type", "page", "size")),
            Map.entry("/security-audit/integrity", Set.of()),
            Map.entry("/ops/tasks", Set.of("status", "kind", "page", "size")),
            Map.entry("/ops/operation-log", Set.of("from", "to", "action", "targetUsername", "page", "size")),
            Map.entry("/dashboard/snapshot", Set.of()),
            Map.entry("/exports/daily", Set.of("from", "to", "format")),
            Map.entry("/exports/events", Set.of("cursor", "since", "limit", "types")),
            Map.entry("/templates", Set.of()));
    private final ClosetManagementClient client;
    private final AuditService audit;
    private final ObjectMapper mapper;
    public ClosetManagementService(ClosetManagementClient client, AuditService audit, ObjectMapper mapper) {
        this.client = client; this.audit = audit; this.mapper = mapper;
    }
    public JsonNode get(String path, Map<String, List<String>> query) {
        validate(path, query);
        return client.get(path, query);
    }
    public PageResponse<JsonNode> page(String path, Map<String, List<String>> query) {
        validate(path, query);
        int page = integer(query, "page", 0, 0, Integer.MAX_VALUE - 1);
        int size = integer(query, "size", 20, 1, 100);
        var upstream = new LinkedHashMap<>(query);
        upstream.put("page", List.of(Integer.toString(page + 1)));
        upstream.put("size", List.of(Integer.toString(size)));
        var result = client.get(path, upstream);
        var items = new ArrayList<JsonNode>();
        result.path("items").forEach(items::add);
        long total = result.path("total").asLong();
        int responseSize = result.path("size").asInt(size);
        return new PageResponse<>(items, result.path("page").asInt(page + 1) - 1, responseSize, total,
                (int) ((total + responseSize - 1) / responseSize));
    }
    /**
     * The closet seals finished days before verifying, so this read changes upstream state; the platform audits the
     * check and its verdict (status only, never problem details).
     */
    public JsonNode integrity(Map<String, List<String>> query) {
        validate("/security-audit/integrity", query);
        try {
            var result = client.get("/security-audit/integrity", query);
            audit.event("CLOSET_SECURITY_AUDIT_INTEGRITY_CHECKED", "ClosetSecurityAudit", "integrity", "SUCCESS",
                    mapper.writeValueAsString(Map.of("status", result.path("status").asText())));
            return result;
        } catch (ApiException e) {
            audit.event("CLOSET_SECURITY_AUDIT_INTEGRITY_CHECKED", "ClosetSecurityAudit", "integrity", "FAILURE",
                    mapper.writeValueAsString(Map.of("code", e.error().name())));
            throw e;
        }
    }
    public byte[] dailyCsv(Map<String, List<String>> query) {
        validate("/exports/daily", query);
        return client.getBytes("/exports/daily", query);
    }
    public JsonNode review(String id, ClosetTemplateReviewRequest request) {
        if (request.reviewStatus() == null && request.enabled() == null && request.bonus() == null)
            throw ApiException.invalidParameter();
        var body = new LinkedHashMap<String, Object>();
        if (request.reviewStatus() != null) body.put("reviewStatus", request.reviewStatus());
        if (request.enabled() != null) body.put("enabled", request.enabled());
        if (request.bonus() != null) body.put("bonus", request.bonus());
        try {
            var result = client.patch("/templates/" + id, body);
            audit.event("CLOSET_TEMPLATE_REVIEW", "ClosetTemplate", id, "SUCCESS", mapper.writeValueAsString(body));
            return result;
        } catch (ApiException e) {
            audit.event("CLOSET_TEMPLATE_REVIEW", "ClosetTemplate", id, "FAILURE", mapper.writeValueAsString(Map.of("code", e.error().name())));
            throw e;
        }
    }
    private static void validate(String path, Map<String, List<String>> query) {
        Set<String> allowed = QUERIES.get(path);
        if (allowed == null) throw ApiException.notFound();
        if (query.keySet().stream().anyMatch(key -> !allowed.contains(key))) throw ApiException.invalidParameter();
        if (path.equals("/invites") || path.equals("/account-events") || path.equals("/security-audit")) {
            if (query.values().stream().anyMatch(values -> values.size() != 1)) throw ApiException.invalidParameter();
            if (path.equals("/invites")) {
                matches(query, "status", "^(ACTIVE|USED_UP|EXPIRED|REVOKED)$");
            } else {
                matches(query, "username", "^[A-Za-z0-9_.-]{2,80}$");
                matches(query, "action", "^[A-Z_]{1,48}$");
                matches(query, "category", "^(AUTH|ACCESS|ACCOUNT|INVITE|ASSISTANT|MANAGEMENT|MODEL)$");
                matches(query, "type", "^[A-Za-z0-9_]{1,64}$");
                if (query.containsKey("actor") && query.get("actor").getFirst().length() > 200) throw ApiException.invalidParameter();
                for (String key : List.of("from", "to")) {
                    if (query.containsKey(key)) {
                        matches(query, key, "^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
                        try { LocalDate.parse(query.get(key).getFirst()); }
                        catch (DateTimeParseException e) { throw ApiException.invalidParameter(); }
                    }
                }
            }
        }
    }
    private static void matches(Map<String, List<String>> query, String key, String pattern) {
        if (query.containsKey(key) && !query.get(key).getFirst().matches(pattern)) throw ApiException.invalidParameter();
    }
    private static int integer(Map<String, List<String>> query, String key, int fallback, int min, int max) {
        if (!query.containsKey(key)) return fallback;
        try {
            var values = query.get(key);
            if (values.size() != 1) throw ApiException.invalidParameter();
            int value = Integer.parseInt(values.getFirst());
            if (value < min || value > max) throw ApiException.invalidParameter();
            return value;
        } catch (NumberFormatException e) { throw ApiException.invalidParameter(); }
    }
}
