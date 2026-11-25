package com.yumg.starter.modules.integrations.closet.infrastructure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.yumg.starter.common.api.ApiErrorCode;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.common.api.FieldViolation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

public class ClosetProblemTranslator {
    private final ObjectMapper mapper;
    public ClosetProblemTranslator(ObjectMapper mapper) { this.mapper = mapper; }

    public ApiException translate(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        JsonNode problem = parse(exception.getResponseBodyAsString());
        String code = problem.path("code").asText("");
        String detail = problem.path("detail").asText("");
        // A source-account challenge is distinct from the administrator's own gateway login.
        if (status.value() == 401 && "BAD_CREDENTIALS".equals(code))
            return ApiException.fromUpstream(ApiErrorCode.CLOSET_PRESET_SOURCE_BAD_CREDENTIALS, detail, List.of());
        if (status.value() == 401 && "PRESET_SOURCE_SESSION_EXPIRED".equals(code))
            return ApiException.fromUpstream(ApiErrorCode.CLOSET_PRESET_SOURCE_SESSION_EXPIRED, detail, List.of());
        if (status.value() == 404 && ("PRESET_NOT_FOUND".equals(code) || "COVER_NOT_FOUND".equals(code)))
            return ApiException.fromUpstream(ApiErrorCode.NOT_FOUND, detail, List.of());
        if (status.value() == 413 && ("PAYLOAD_TOO_LARGE".equals(code) || "COVER_TOO_LARGE".equals(code)))
            return ApiException.fromUpstream(ApiErrorCode.CLOSET_REQUEST_TOO_LARGE, detail, List.of());
        if (code.startsWith("FEEDBACK_")) {
            ApiErrorCode mapped = switch (status.value()) {
                case 404 -> ApiErrorCode.NOT_FOUND;
                case 409 -> ApiErrorCode.CONFLICT;
                default -> status.is5xxServerError() ? ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE : ApiErrorCode.INVALID_PARAMETER;
            };
            if (mapped == ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE) return ApiException.closetServiceUnavailable();
            return ApiException.fromUpstream(mapped, detail, List.of());
        }
        if (code.startsWith("LOOK_") || code.startsWith("POSE_") || code.startsWith("CATEGORY_")) {
            // Administrators fix rejected poses from the hits; each hit becomes {field: kind, message: "term：reason"}.
            if (status.value() == 422 && code.startsWith("POSE_"))
                return ApiException.fromUpstream(ApiErrorCode.CLOSET_POSE_REJECTED, detail, hits(problem.path("hits")));
            ApiErrorCode mapped = switch (status.value()) {
                case 404 -> ApiErrorCode.NOT_FOUND;
                case 409 -> ApiErrorCode.CONFLICT;
                case 413 -> ApiErrorCode.CLOSET_REQUEST_TOO_LARGE;
                case 429 -> ApiErrorCode.RATE_LIMITED;
                default -> status.is5xxServerError() ? ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE : ApiErrorCode.INVALID_PARAMETER;
            };
            if (mapped == ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE) return ApiException.closetServiceUnavailable();
            return ApiException.fromUpstream(mapped, detail, List.of());
        }
        if (code.startsWith("SAMPLE_") || code.startsWith("OOTD_SAMPLE_")) {
            ApiErrorCode mapped = switch (status.value()) {
                case 404 -> ApiErrorCode.NOT_FOUND;
                case 409 -> ApiErrorCode.CONFLICT;
                case 413 -> ApiErrorCode.CLOSET_REQUEST_TOO_LARGE;
                case 429 -> ApiErrorCode.RATE_LIMITED;
                default -> status.is5xxServerError() ? ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE : ApiErrorCode.INVALID_PARAMETER;
            };
            if (mapped == ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE) return ApiException.closetServiceUnavailable();
            return ApiException.fromUpstream(mapped, detail, List.of());
        }
        if (code.startsWith("SHOWCASE_")) {
            ApiErrorCode mapped = switch (status.value()) {
                case 403 -> ApiErrorCode.ACCESS_DENIED;
                case 404 -> ApiErrorCode.NOT_FOUND;
                case 409 -> ApiErrorCode.CONFLICT;
                case 413 -> ApiErrorCode.CLOSET_REQUEST_TOO_LARGE;
                default -> status.is5xxServerError() ? ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE : ApiErrorCode.INVALID_PARAMETER;
            };
            return ApiException.fromUpstream(mapped, detail, List.of());
        }
        if (code.startsWith("TRYON_")) {
            // Preserve control-plane failures instead of turning stale revisions or missing sessions into HTTP 400.
            ApiErrorCode mapped = switch (status.value()) {
                case 404 -> ApiErrorCode.NOT_FOUND;
                case 409 -> ApiErrorCode.CONFLICT;
                case 429 -> ApiErrorCode.RATE_LIMITED;
                default -> status.is5xxServerError() ? ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE : ApiErrorCode.INVALID_PARAMETER;
            };
            // Provider details may contain sensitive data; only known revision guidance is exposed.
            String message = "TRYON_REVISION_CONFLICT".equals(code)
                    ? "试穿设置已变化，请刷新后重新编辑并保存。"
                    : mapped.message();
            return ApiException.fromUpstream(mapped, message, List.of());
        }
        if (java.util.Set.of("TIER_NOT_FOUND", "BATCH_NOT_FOUND", "ENTITLEMENT_NOT_FOUND", "PRODUCT_NOT_FOUND", "ORDER_NOT_FOUND").contains(code)
                && status.value() == 404) return ApiException.fromUpstream(ApiErrorCode.NOT_FOUND, detail, List.of());
        if (status.value() == 409 && !"LAST_ADMIN_PROTECTED".equals(code)) return ApiException.fromUpstream(ApiErrorCode.CONFLICT, detail, List.of());
        if (status.value() == 429) return ApiException.fromUpstream(ApiErrorCode.RATE_LIMITED, detail, List.of());
        if (status.is5xxServerError()) return ApiException.closetServiceUnavailable();
        if (status.value() == 401 || code.startsWith("MANAGEMENT_")) return ApiException.closetIntegrationMisconfigured();
        if (status.value() == 400 && "VALIDATION_FAILED".equals(code)) {
            return ApiException.fromUpstream(ApiErrorCode.VALIDATION_FAILED, detail, violations(problem.path("violations")));
        }
        if (status.value() == 404 && ("USER_NOT_FOUND".equals(code) || "PORTABLE_LINK_NOT_FOUND".equals(code) || "TEMPLATE_NOT_FOUND".equals(code) || "STYLE_TERM_NOT_FOUND".equals(code)
                || "SESSION_NOT_FOUND".equals(code) || "INVITE_NOT_FOUND".equals(code))) {
            return ApiException.fromUpstream(ApiErrorCode.NOT_FOUND, detail, List.of());
        }
        if (status.value() == 409 && "LAST_ADMIN_PROTECTED".equals(code)) return ApiException.closetLastAdminProtected();
        if (status.value() == 409 && ("USER_ALREADY_EXISTS".equals(code) || "INVITE_REVOKED".equals(code))) {
            return ApiException.fromUpstream(ApiErrorCode.CONFLICT, detail, List.of());
        }
        if (status.value() == 413) return ApiException.fromUpstream(ApiErrorCode.INVALID_PARAMETER, detail, List.of());
        return ApiException.fromUpstream(ApiErrorCode.INVALID_PARAMETER, detail, List.of());
    }

    public ApiException translate(ResourceAccessException exception) { return ApiException.closetServiceUnavailable(); }

    private JsonNode parse(String body) {
        try { return mapper.readTree(body); } catch (Exception ignored) { return mapper.createObjectNode(); }
    }
    private List<FieldViolation> hits(JsonNode nodes) {
        List<FieldViolation> result = new ArrayList<>();
        if (nodes.isArray()) for (JsonNode node : nodes) {
            String kind = node.path("kind").asText("");
            if (kind.isEmpty()) continue;
            String term = node.path("term").asText("");
            String reason = node.path("reason").asText("");
            result.add(new FieldViolation(kind, term.isEmpty() ? reason : reason.isEmpty() ? term : term + "：" + reason));
        }
        return List.copyOf(result);
    }
    private List<FieldViolation> violations(JsonNode nodes) {
        List<FieldViolation> result = new ArrayList<>();
        if (nodes.isArray()) for (JsonNode node : nodes) {
            if (node.hasNonNull("field") && node.hasNonNull("message")) {
                result.add(new FieldViolation(node.get("field").asText(), node.get("message").asText()));
            }
        }
        return List.copyOf(result);
    }
}
