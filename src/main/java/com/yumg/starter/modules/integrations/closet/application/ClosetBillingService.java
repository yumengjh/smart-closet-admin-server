package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetBillingService {
    private final ClosetManagementClient client;
    private final AuditService audit;
    public ClosetBillingService(ClosetManagementClient client, AuditService audit) {
        this.client = client; this.audit = audit;
    }
    public JsonNode read(String path, Map<String, List<String>> query) { return client.get(path, query); }

    /** Never audit request/response bodies: creation responses contain one-time redemption codes. */
    public JsonNode write(String action, String method, String path, Object body, String key) {
        try {
            var result = switch (method) {
                case "POST" -> client.post(path, body, key);
                case "PUT" -> client.put(path, body);
                case "DELETE" -> client.deleteWithResponse(path);
                default -> throw new IllegalArgumentException("Unsupported billing operation");
            };
            audit.event("CLOSET_BILLING_" + action, "ClosetBilling", path, "SUCCESS", null);
            return result;
        } catch (ApiException exception) {
            audit.event("CLOSET_BILLING_" + action, "ClosetBilling", path, "FAILURE", exception.error().name());
            throw exception;
        }
    }
}
