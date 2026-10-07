package com.yumg.starter.modules.integrations.closet.infrastructure;

import tools.jackson.databind.ObjectMapper;
import com.yumg.starter.modules.integrations.closet.application.ClosetRuntimeComponent;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ClosetIntegrationProperties.class)
public class ClosetIntegrationConfiguration {
    @Bean ClosetRequestSigner closetRequestSigner() { return new ClosetRequestSigner(); }
    @Bean ClosetProblemTranslator closetProblemTranslator(ObjectMapper mapper) { return new ClosetProblemTranslator(mapper); }
    @Bean(name = "closetManagementRestClientBuilder") RestClient.Builder closetManagementRestClientBuilder(ClosetIntegrationProperties properties) {
        return restClientBuilder(properties.getConnectTimeout(), properties.getReadTimeout());
    }
    @Bean ClosetManagementClient closetManagementClient(
            @org.springframework.beans.factory.annotation.Qualifier("closetManagementRestClientBuilder") RestClient.Builder builder,
            ClosetIntegrationProperties properties, ClosetRequestSigner signer, ClosetProblemTranslator translator, ObjectMapper mapper) {
        return new ClosetManagementClient(builder,
                properties, signer, translator, mapper);
    }
    @Bean ClosetRuntimeComponent closetRuntimeComponent(ClosetIntegrationProperties properties,
            ClosetRequestSigner signer, ClosetProblemTranslator translator, ObjectMapper mapper) {
        ClosetManagementClient probe = new ClosetManagementClient(restClientBuilder(Duration.ofSeconds(2), Duration.ofSeconds(2)),
                properties, signer, translator, mapper);
        return new ClosetRuntimeComponent(probe);
    }
    private RestClient.Builder restClientBuilder(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }
}
