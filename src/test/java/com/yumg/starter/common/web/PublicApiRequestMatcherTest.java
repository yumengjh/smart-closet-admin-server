package com.yumg.starter.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumg.starter.StarterApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

@SpringBootTest(classes = StarterApplication.class, properties =
        "spring.datasource.url=jdbc:sqlite:target/public-api-request-matcher-test.db")
class PublicApiRequestMatcherTest {

    @Autowired
    private PublicApiRequestMatcher matcher;

    @Test
    void recognizesOnlyControllerMethodsMarkedAsPublic() {
        assertThat(matcher.matches(new MockHttpServletRequest("GET", "/api/v1/announcements"))).isTrue();
        assertThat(matcher.matches(new MockHttpServletRequest("GET", "/api/v1/announcements/"))).isTrue();
        assertThat(matcher.matches(new MockHttpServletRequest("GET", "/api/v1/announcements/announcement-id"))).isTrue();
        assertThat(matcher.matches(new MockHttpServletRequest("GET", "/api/v1/announcements/manage"))).isFalse();
        assertThat(matcher.matches(new MockHttpServletRequest("GET", "/api/v1/announcements/manage/announcement-id"))).isFalse();
    }
}
