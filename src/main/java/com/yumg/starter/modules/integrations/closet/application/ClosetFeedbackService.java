package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetFeedbackRequests;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Relays Fish Closet user feedback. Closet stores it; this platform pages it 0-based and audits each change with field names and
 * statuses only, never the text, the reply, the note or the contact.
 */
@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetFeedbackService {
    private static final String BASE = "/feedback";
    private static final Set<String> FILTERS = Set.of("status", "category", "submitter", "q", "from", "to");
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F-]{36}");
    private final ClosetManagementClient client;
    private final AuditService audit;
    private final ObjectMapper mapper;
    public ClosetFeedbackService(ClosetManagementClient client, AuditService audit, ObjectMapper mapper) {
        this.client = client; this.audit = audit; this.mapper = mapper;
    }

    /** One page in the platform's 0-based {@code PageResponse} shape, plus the status counts for the tabs. */
    public JsonNode list(Map<String, List<String>> query) {
        var upstream = new LinkedHashMap<String, List<String>>();
        for (var entry : query.entrySet()) {
            String key = entry.getKey();
            if (key.equals("page") || key.equals("size")) continue;
            if (!FILTERS.contains(key) || entry.getValue().size() != 1) throw ApiException.invalidParameter();
            String value = entry.getValue().getFirst();
            if (value == null || value.isBlank()) continue;
            if (value.length() > 100) throw ApiException.invalidParameter();
            upstream.put(key, List.of(value));
        }
        int page = integer(query, "page", 0, 0, Integer.MAX_VALUE - 1);
        int size = integer(query, "size", 20, 1, 100);
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
        out.set("counts", result.path("counts").isObject() ? result.path("counts") : mapper.createObjectNode());
        return out;
    }

    public JsonNode settings() { return client.get(BASE + "/settings", Map.of()); }

    public JsonNode putSettings(ClosetFeedbackRequests.Settings request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("anonymousEnabled", request.anonymousEnabled()); body.put("revision", request.revision());
        var detail = new LinkedHashMap<String, Object>();
        detail.put("anonymousEnabled", request.anonymousEnabled());
        return audited("CLOSET_FEEDBACK_SETTINGS", "settings", detail, () -> client.put(BASE + "/settings", body));
    }

    public JsonNode edit(String id, ClosetFeedbackRequests.Edit request) {
        requireId(id);
        var body = new LinkedHashMap<String, Object>();
        body.put("revision", request.revision());
        var fields = new ArrayList<String>();
        if (request.status() != null) { body.put("status", request.status()); fields.add("status"); }
        if (request.reply() != null) { body.put("reply", request.reply()); fields.add("reply"); }
        if (request.note() != null) { body.put("note", request.note()); fields.add("note"); }
        var detail = new LinkedHashMap<String, Object>();
        detail.put("fields", fields);
        if (request.status() != null) detail.put("status", request.status());
        return audited("CLOSET_FEEDBACK_UPDATE", id, detail, () -> client.patch(BASE + "/" + id, body));
    }

    public void delete(String id) {
        requireId(id);
        audited("CLOSET_FEEDBACK_DELETE", id, new LinkedHashMap<>(), () -> { client.delete(BASE + "/" + id); return null; });
    }

    private JsonNode audited(String action, String target, Map<String, Object> detail, Supplier<JsonNode> call) {
        try {
            JsonNode result = call.get();
            audit.event(action, "ClosetFeedback", target, "SUCCESS", mapper.writeValueAsString(detail));
            return result;
        } catch (ApiException e) {
            detail.put("errorCode", e.error().name());
            audit.event(action, "ClosetFeedback", target, "FAILURE", mapper.writeValueAsString(detail));
            throw e;
        } catch (RuntimeException e) {
            detail.put("errorCode", "CLOSET_SERVICE_UNAVAILABLE");
            audit.event(action, "ClosetFeedback", target, "FAILURE", mapper.writeValueAsString(detail));
            throw ApiException.closetServiceUnavailable();
        }
    }

    private static void requireId(String id) { if (id == null || !ID.matcher(id).matches()) throw ApiException.notFound(); }

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
