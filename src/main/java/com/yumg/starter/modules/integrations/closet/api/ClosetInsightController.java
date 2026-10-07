package com.yumg.starter.modules.integrations.closet.api;

import tools.jackson.databind.JsonNode;
import com.yumg.starter.modules.integrations.closet.application.ClosetInsightService;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet/insights")
public class ClosetInsightController {
    private final ClosetInsightService insights;
    public ClosetInsightController(ClosetInsightService insights) { this.insights = insights; }
    @GetMapping("/{report}") @PreAuthorize("hasAuthority('closet:insights:read')")
    public JsonNode report(@PathVariable String report,
                           @org.springframework.web.bind.annotation.RequestParam MultiValueMap<String, String> params) {
        Map<String, List<String>> query = new java.util.LinkedHashMap<>();
        params.forEach((key, values) -> query.put(key, List.copyOf(values)));
        return insights.get(report, query);
    }
}
