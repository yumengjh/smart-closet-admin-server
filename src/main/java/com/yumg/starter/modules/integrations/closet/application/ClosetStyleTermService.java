package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetStyleTermBatchRequest;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetStyleTermReviewRequest;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Review of style words the assistant learned from chat. Closet stores them; this platform only relays and audits the decision. */
@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetStyleTermService {
    private static final String BASE = "/style-terms/pending";
    private final ClosetManagementClient client;
    private final AuditService audit;
    private final ObjectMapper mapper;
    public ClosetStyleTermService(ClosetManagementClient client, AuditService audit, ObjectMapper mapper) {
        this.client = client; this.audit = audit; this.mapper = mapper;
    }

    /** Queue page in the platform's 0-based {@code PageResponse} shape, plus the vocabulary a reviewer may map a word to. */
    public JsonNode pending(Map<String, List<String>> query) {
        if (query.keySet().stream().anyMatch(key -> !key.equals("page") && !key.equals("size"))) throw ApiException.invalidParameter();
        int page = integer(query, "page", 0, 0, Integer.MAX_VALUE - 1);
        int size = integer(query, "size", 20, 1, 100);
        var upstream = new LinkedHashMap<String, List<String>>();
        upstream.put("page", List.of(Integer.toString(page + 1)));
        upstream.put("size", List.of(Integer.toString(size)));
        JsonNode result = client.get(BASE, upstream);
        int responseSize = result.path("size").asInt(size);
        long total = result.path("total").asLong();
        var out = mapper.createObjectNode();
        out.set("items", result.path("items").isArray() ? result.path("items") : mapper.createArrayNode());
        out.put("page", result.path("page").asInt(page + 1) - 1);
        out.put("size", responseSize);
        out.put("totalElements", total);
        out.put("totalPages", (int) ((total + responseSize - 1) / responseSize));
        out.set("vocabulary", result.path("vocabulary").isArray() ? result.path("vocabulary") : mapper.createArrayNode());
        return out;
    }

    public JsonNode approve(ClosetStyleTermReviewRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("term", request.term()); body.put("proposedStyles", request.proposedStyles());
        if (request.styles() != null && !request.styles().isEmpty()) body.put("styles", request.styles());
        return audited("CLOSET_STYLE_TERM_APPROVE", "/approve", body, request.term(), request.proposedStyles(), request.styles());
    }

    public JsonNode reject(ClosetStyleTermReviewRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("term", request.term()); body.put("proposedStyles", request.proposedStyles());
        return audited("CLOSET_STYLE_TERM_REJECT", "/reject", body, request.term(), request.proposedStyles(), null);
    }

    /** Every item is decided on its own and audited on its own; one failure does not stop the others. */
    public JsonNode batch(ClosetStyleTermBatchRequest request) {
        boolean approve = "APPROVE".equals(request.action());
        if (request.action() == null) throw ApiException.invalidParameter();
        var results = mapper.createArrayNode();
        int ok = 0;
        for (var item : request.items()) {
            var row = mapper.createObjectNode();
            row.put("term", item.term()); row.set("proposedStyles", mapper.valueToTree(item.proposedStyles()));
            try {
                var single = new ClosetStyleTermReviewRequest(item.term(), item.proposedStyles(), null);
                if (approve) approve(single); else reject(single);
                row.put("ok", true); ok++;
            } catch (ApiException e) {
                row.put("ok", false); row.put("code", e.error().name());
            }
            results.add(row);
        }
        var out = mapper.createObjectNode();
        out.put("succeeded", ok); out.put("failed", request.items().size() - ok); out.set("results", results);
        return out;
    }

    private JsonNode audited(String action, String suffix, Map<String, Object> body, String term, List<String> proposed, List<String> corrected) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("term", term); detail.put("proposedStyles", proposed);
        if (corrected != null && !corrected.isEmpty()) detail.put("styles", corrected);
        try {
            JsonNode result = client.post(BASE + suffix, body);
            audit.event(action, "ClosetStyleTerm", term, "SUCCESS", mapper.writeValueAsString(detail));
            return result;
        } catch (ApiException e) {
            detail.put("errorCode", e.error().name());
            audit.event(action, "ClosetStyleTerm", term, "FAILURE", mapper.writeValueAsString(detail));
            throw e;
        } catch (RuntimeException e) {
            detail.put("errorCode", "CLOSET_SERVICE_UNAVAILABLE");
            audit.event(action, "ClosetStyleTerm", term, "FAILURE", mapper.writeValueAsString(detail));
            throw ApiException.closetServiceUnavailable();
        }
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
