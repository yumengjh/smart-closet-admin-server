package com.yumg.starter.modules.integrations.closet.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

/** Leave only this signed raw-body endpoint untouched by servlet multipart parsing. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetShowcaseMultipartConfiguration {
    public static final String UPLOAD_PATH = "/api/v1/closet/creations/showcases/uploads";
    @Bean(name = "multipartResolver")
    StandardServletMultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver() {
            @Override public boolean isMultipart(HttpServletRequest request) {
                return !("POST".equals(request.getMethod())
                        && UPLOAD_PATH.equals(request.getRequestURI().substring(request.getContextPath().length())))
                        && super.isMultipart(request);
            }
        };
    }
}
