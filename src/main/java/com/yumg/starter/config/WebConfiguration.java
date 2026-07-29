package com.yumg.starter.config;

import com.yumg.starter.common.web.ClientIpResolver;
import com.yumg.starter.common.web.ResponseIdentityHeaderFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WebConfiguration {
    @Bean ClientIpResolver clientIpResolver(@Value("${app.security.trusted-proxies:}") String trustedProxies) {
        return new ClientIpResolver(trustedProxies);
    }

    @Bean
    ResponseIdentityHeaderFilter responseIdentityHeaderFilter(
            ResponseIdentityProperties properties) {
        return new ResponseIdentityHeaderFilter(
                properties.poweredBy(), properties.server());
    }
}
