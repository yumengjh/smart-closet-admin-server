package com.yumg.starter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResponseIdentityPropertiesTest {

    @Test
    void usesCamouflageDefaultsWhenValuesAreMissingOrBlank() {
        var properties = new ResponseIdentityProperties(null, "   ");

        assertThat(properties.poweredBy()).isEqualTo("ASP.NET");
        assertThat(properties.server()).isEqualTo("Microsoft-IIS/10.0");
    }

    @Test
    void keepsConfiguredIdentitiesAfterTrimmingOuterWhitespace() {
        var properties = new ResponseIdentityProperties("  PHP/8.4  ", "  nginx  ");

        assertThat(properties.poweredBy()).isEqualTo("PHP/8.4");
        assertThat(properties.server()).isEqualTo("nginx");
    }

    @Test
    void rejectsControlCharactersThatCouldCreateAnotherHeader() {
        assertThatThrownBy(() -> new ResponseIdentityProperties(
                "ASP.NET\r\nInjected: true", "Microsoft-IIS/10.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");

        assertThatThrownBy(() -> new ResponseIdentityProperties(
                "ASP.NET", "Microsoft-IIS/10.0\nInjected: true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
    }

    @Test
    void rejectsValuesBeyondTheDocumentedHeaderLimit() {
        assertThatThrownBy(() -> new ResponseIdentityProperties(
                "A".repeat(129), "Microsoft-IIS/10.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128 characters");

        assertThatThrownBy(() -> new ResponseIdentityProperties(
                "ASP.NET", "A".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128 characters");
    }
}
