package com.yumg.starter.modules.integrations.closet.application;

import tools.jackson.databind.JsonNode;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetInsightService {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "recommendations", Set.of("username", "from", "to"),
            "ai", Set.of("username", "from", "to"),
            "overview", Set.of("username", "from", "to"),
            "wardrobe", Set.of("username"),
            "usage", Set.of("username"),
            "trends", Set.of("username", "from", "to", "metric", "granularity"));
    private final ClosetManagementClient client;
    public ClosetInsightService(ClosetManagementClient client) { this.client = client; }
    public JsonNode get(String report, Map<String, List<String>> parameters) {
        Set<String> allowed = ALLOWED.get(report);
        if (allowed == null) throw ApiException.notFound();
        if (parameters.keySet().stream().anyMatch(key -> !allowed.contains(key))) throw ApiException.invalidParameter();
        Map<String, List<String>> query = new LinkedHashMap<>();
        parameters.forEach((key, values) -> query.put(key, List.copyOf(values)));
        return client.get("/insights/" + report, query);
    }
}
