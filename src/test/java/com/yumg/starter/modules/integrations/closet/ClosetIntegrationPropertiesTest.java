package com.yumg.starter.modules.integrations.closet;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetIntegrationProperties;
import org.junit.jupiter.api.Test;

class ClosetIntegrationPropertiesTest {
    @Test void validationIsSkippedWhileIntegrationIsDisabled() {
        ClosetIntegrationProperties properties = new ClosetIntegrationProperties();
        properties.setSecret("");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
    @Test void enabledConfigurationRequiresHttpUrlAndAtLeast32SecretBytes() {
        ClosetIntegrationProperties properties = new ClosetIntegrationProperties();
        properties.setEnabled(true);
        properties.setSecret("short");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 个 UTF-8 字节");
        properties.setSecret("0123456789abcdef0123456789abcdef");
        properties.setBaseUrl("ftp://closet.example");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class).hasMessageContaining("http 或 https");
        properties.setBaseUrl("http://closet.example");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
