package com.yumg.starter.modules.integrations.closet;

import static org.assertj.core.api.Assertions.assertThat;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetRequestSigner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClosetRequestSignerTest {
    @Test void matchesTheFrozenInteroperabilityVector() {
        ClosetRequestSigner signer = new ClosetRequestSigner();
        String canonical = signer.canonical("GET", "/api/manage/v1/users",
                Map.of("q", List.of("a b"), "size", List.of("1")), 1790000000000L,
                "nonce-0123456789abcdef", "admin", "trace-1", new byte[0]);
        assertThat(canonical).isEqualTo("CLOSET-HMAC-SHA256\nGET\n/api/manage/v1/users\nq=a%20b&size=1\n1790000000000\nnonce-0123456789abcdef\nadmin\ntrace-1\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(signer.sign(canonical, "0123456789abcdef0123456789abcdef"))
                .isEqualTo("QrNFOGRrxbMHYQK3h_5AzEq2QbFkYErcWAuygoZ8r88");
    }

    @Test void sortsEncodedPairsLikeTheClosetVerifier() {
        // Raw order would put "z" first; encoded order puts "%C3%A9" first.
        assertThat(ClosetRequestSigner.canonicalQuery(Map.of("size", List.of("1"), "q", List.of("z", "é"))))
                .isEqualTo("q=%C3%A9&q=z&size=1");
    }
}
