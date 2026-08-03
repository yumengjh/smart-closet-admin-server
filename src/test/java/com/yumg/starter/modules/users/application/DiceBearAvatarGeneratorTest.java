package com.yumg.starter.modules.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DiceBearAvatarGeneratorTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void requestsOnlyTheFixedLoreleiEndpointAndAcceptsSafeSvg() throws Exception {
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\"><defs><path id=\"a\" "
                + "d=\"M0 0\"/></defs><use href=\"#a\"/></svg>")
                .getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/10.x/lorelei/svg", exchange -> {
            assertThat(exchange.getRequestURI().getQuery()).contains("seed=stable-seed", "size=256");
            exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
            exchange.sendResponseHeaders(200, svg.length);
            exchange.getResponseBody().write(svg);
            exchange.close();
        });
        server.start();

        byte[] generated = generator().generate("stable-seed", 256);

        assertThat(generated).containsExactly(svg);
    }

    @Test
    void rejectsActiveOrExternalSvgContent() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/10.x/lorelei/svg", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
            exchange.sendResponseHeaders(200, svg.length);
            exchange.getResponseBody().write(svg);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> generator().generate("stable-seed", 256))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsafe SVG");
    }

    @Test
    void rejectsProtocolRelativeExternalSvgReferences() throws Exception {
        byte[] svg = ("<svg xmlns=\"http://www.w3.org/2000/svg\">"
                + "<image href=\"//example.invalid/avatar.png\"/></svg>")
                .getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/10.x/lorelei/svg", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
            exchange.sendResponseHeaders(200, svg.length);
            exchange.getResponseBody().write(svg);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> generator().generate("stable-seed", 256))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsafe SVG");
    }

    private DiceBearAvatarGenerator generator() {
        return new DiceBearAvatarGenerator(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 256 * 1024);
    }
}
