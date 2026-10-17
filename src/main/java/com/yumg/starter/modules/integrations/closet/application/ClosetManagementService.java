package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.common.api.PageResponse;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetTemplateReviewRequest;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.security.application.AuditService;
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
    private static final Map<String, Set<String>> QUERIES = Map.of(
            "/ops/status", Set.of(),
            "/ops/tasks", Set.of("status", "kind", "page", "size"),
            "/ops/operation-log", Set.of("from", "to", "action", "targetUsername", "page", "size"),
            "/dashboard/snapshot", Set.of(),
            "/exports/daily", Set.of("from", "to", "format"),
            "/exports/events", Set.of("cursor", "since", "limit", "types"),
            "/templates", Set.of());
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
        int page = integer(query, "page", 0, 0, 99999);
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
