package com.yumg.starter.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ResponseIdentityHeaderFilterTest {

    @Test
    void addsTheConfiguredIdentitiesToEveryResponse() throws Exception {
        var filter = new ResponseIdentityHeaderFilter("ASP.NET", "Microsoft-IIS/10.0");
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response,
                (ignoredRequest, ignoredResponse) -> {});

        assertThat(response.getHeader(ResponseIdentityHeaderFilter.POWERED_BY_HEADER_NAME))
                .isEqualTo("ASP.NET");
        assertThat(response.getHeader(ResponseIdentityHeaderFilter.SERVER_HEADER_NAME))
                .isEqualTo("Microsoft-IIS/10.0");
    }

    @Test
    void replacesIdentitiesSetByDownstreamCode() throws Exception {
        var filter = new ResponseIdentityHeaderFilter("PHP/8.4", "nginx");
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response,
                (ignoredRequest, downstreamResponse) -> {
                    var httpResponse = (HttpServletResponse) downstreamResponse;
                    httpResponse.setHeader(
                            ResponseIdentityHeaderFilter.POWERED_BY_HEADER_NAME, "Spring Boot");
                    httpResponse.setHeader(
                            ResponseIdentityHeaderFilter.SERVER_HEADER_NAME, "Apache Tomcat");
                });

        assertThat(response.getHeader(ResponseIdentityHeaderFilter.POWERED_BY_HEADER_NAME))
                .isEqualTo("PHP/8.4");
        assertThat(response.getHeader(ResponseIdentityHeaderFilter.SERVER_HEADER_NAME))
                .isEqualTo("nginx");
    }

    @Test
    void restoresBothIdentitiesAfterDownstreamCodeResetsTheResponse() throws Exception {
        var filter = new ResponseIdentityHeaderFilter("ASP.NET", "Microsoft-IIS/10.0");
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response,
                (ignoredRequest, downstreamResponse) ->
                        ((HttpServletResponse) downstreamResponse).reset());

        assertThat(response.getHeader(ResponseIdentityHeaderFilter.POWERED_BY_HEADER_NAME))
                .isEqualTo("ASP.NET");
        assertThat(response.getHeader(ResponseIdentityHeaderFilter.SERVER_HEADER_NAME))
                .isEqualTo("Microsoft-IIS/10.0");
    }
}
