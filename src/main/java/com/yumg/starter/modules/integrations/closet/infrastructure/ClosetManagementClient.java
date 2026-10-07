package com.yumg.starter.modules.integrations.closet.infrastructure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public class ClosetManagementClient {
    private final RestClient.Builder restClientBuilder;
    private volatile RestClient cachedClient;
    private final ClosetIntegrationProperties properties;
    private final ClosetRequestSigner signer;
    private final ClosetProblemTranslator translator;
    private final ObjectMapper mapper;

    public ClosetManagementClient(RestClient.Builder restClientBuilder, ClosetIntegrationProperties properties,
                                 ClosetRequestSigner signer, ClosetProblemTranslator translator,
                                 ObjectMapper mapper) {
        this.restClientBuilder = restClientBuilder;
        this.properties = properties;
        this.signer = signer;
        this.translator = translator;
        this.mapper = mapper;
    }

    public JsonNode get(String path, Map<String, ? extends List<String>> query) { return exchange(HttpMethod.GET, path, query, null); }
    public JsonNode post(String path, Object body) { return exchange(HttpMethod.POST, path, Map.of(), body); }
    public JsonNode patch(String path, Object body) { return exchange(HttpMethod.PATCH, path, Map.of(), body); }
    public JsonNode put(String path, Object body) { return exchange(HttpMethod.PUT, path, Map.of(), body); }

    public void delete(String path) {
        URI uri = uri(path, Map.of());
        try {
            restClient().method(HttpMethod.DELETE).uri(uri).headers(headers -> headers.addAll(headers(uri, "DELETE", Map.of(), new byte[0])))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) { throw translator.translate(exception); }
        catch (ResourceAccessException exception) { throw translator.translate(exception); }
    }

    public JsonNode deleteWithResponse(String path) {
        URI uri = uri(path, Map.of());
        try {
            return restClient().method(HttpMethod.DELETE).uri(uri)
                    .headers(headers -> headers.addAll(headers(uri, "DELETE", Map.of(), new byte[0])))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException exception) { throw translator.translate(exception); }
        catch (ResourceAccessException exception) { throw translator.translate(exception); }
    }

    private JsonNode exchange(HttpMethod method, String path, Map<String, ? extends List<String>> query, Object body) {
        Map<String, ? extends List<String>> safeQuery = query == null ? Map.of() : query;
        URI uri = uri(path, safeQuery);
        byte[] bytes;
        try { bytes = body == null ? new byte[0] : mapper.writeValueAsBytes(body); }
        catch (Exception exception) { throw new IllegalStateException("Could not serialize closet request", exception); }
        try {
            var spec = restClient().method(method).uri(uri)
                    .headers(headers -> headers.addAll(headers(uri, method.name(), safeQuery, bytes)));
            if (body != null) spec.contentType(MediaType.APPLICATION_JSON).body(bytes);
            return spec.retrieve().body(JsonNode.class);
        } catch (RestClientResponseException exception) { throw translator.translate(exception); }
        catch (ResourceAccessException exception) { throw translator.translate(exception); }
    }

    private org.springframework.http.HttpHeaders headers(URI uri, String method,
            Map<String, ? extends List<String>> query, byte[] body) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String operator = authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                ? jwt.getClaimAsString("username") : null;
        String requestId = MDC.get(com.yumg.starter.common.web.TraceIdFilter.TRACE_ID_MDC_KEY);
        return signer.headers(uri, method, query, body, properties.getKeyId(), properties.getSecret(), operator, requestId);
    }

    private URI uri(String path, Map<String, ? extends List<String>> query) {
        URI base = URI.create(properties.getBaseUrl());
        String basePath = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        String rawPath = basePath + "/api/manage/v1" + path;
        String rawQuery = query.entrySet().stream()
                .flatMap(entry -> (entry.getValue() == null ? List.<String>of("") : entry.getValue()).stream()
                        .map(value -> ClosetRequestSigner.encodeComponent(entry.getKey()) + "="
                                + ClosetRequestSigner.encodeComponent(value == null ? "" : value)))
                .collect(java.util.stream.Collectors.joining("&"));
        String authority = base.getRawAuthority();
        String target = base.getScheme() + "://" + authority + rawPath + (rawQuery.isEmpty() ? "" : "?" + rawQuery);
        return URI.create(target);
    }

    private RestClient restClient() {
        RestClient current = cachedClient;
        if (current != null) return current;
        synchronized (this) {
            if (cachedClient == null) cachedClient = restClientBuilder.build();
            return cachedClient;
        }
    }
}
