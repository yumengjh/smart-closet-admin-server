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
    public static final String TRIAL_PERSON_PATH = "/api/v1/closet/creations/trial-persons";
    public static final String SAMPLE_PATH = "/api/v1/closet/samples";
    @Bean(name = "multipartResolver")
    StandardServletMultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver() {
            @Override public boolean isMultipart(HttpServletRequest request) {
                return !("POST".equals(request.getMethod()) && rawUpload(request.getRequestURI().substring(request.getContextPath().length())))
                        && super.isMultipart(request);
            }
        };
        }
    private static boolean rawUpload(String path) { return UPLOAD_PATH.equals(path) || TRIAL_PERSON_PATH.equals(path) || SAMPLE_PATH.equals(path); }
}
