package com.yumg.starter.common.api;

import com.yumg.starter.common.web.TraceIdFilter;
import com.yumg.starter.common.web.PublicApi;
import com.yumg.starter.common.web.PublicApiAccess;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!request.getURI().getPath().startsWith("/api/v1/") || body == null
                || body instanceof ApiError || body instanceof ApiResponse<?>
                // Raw bytes (images, files) are written by ByteArrayHttpMessageConverter and are never JSON envelopes.
                || body instanceof byte[]) {
            return body;
        }
        PublicApi publicApi = publicApi(returnType);
        if (publicApi != null && publicApi.minimalResponse()
                && !PublicApiAccess.hasAuthority(publicApi.detailedAuthority())) return body;
        String traceId = response.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) traceId = UUID.randomUUID().toString().replace("-", "");
        return new ApiResponse<>(body, traceId, System.currentTimeMillis());
    }

    private PublicApi publicApi(MethodParameter returnType) {
        PublicApi methodAnnotation = returnType.getMethod() == null ? null
                : AnnotatedElementUtils.findMergedAnnotation(returnType.getMethod(), PublicApi.class);
        return methodAnnotation != null ? methodAnnotation
                : AnnotatedElementUtils.findMergedAnnotation(returnType.getContainingClass(), PublicApi.class);
    }
}
