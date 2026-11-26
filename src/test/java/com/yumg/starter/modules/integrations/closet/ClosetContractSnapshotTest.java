package com.yumg.starter.modules.integrations.closet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards docs/integrations/closet/management-api.yaml against drifting from the Fish Closet contract
 * (smart-closet-server docs/reference/management-api.yaml). The snapshot is a 4-line provenance header plus the
 * source verbatim, written by scripts/sync-closet-contract.sh.
 *
 * <ul>
 *   <li>The recorded sha256 must match the body, so the snapshot cannot be hand-edited.</li>
 *   <li>Every upstream operation this service calls must exist in the snapshot.</li>
 *   <li>When the source is readable (sibling checkout, or env/property CLOSET_CONTRACT_SOURCE), the body must
 *       equal it byte for byte. CI cannot read the private source repo, so this part is skipped there.</li>
 * </ul>
 */
class ClosetContractSnapshotTest {
    private static final Path SNAPSHOT = Path.of("docs/integrations/closet/management-api.yaml");
    private static final int HEADER_LINES = 4;
    private static final Pattern SHA = Pattern.compile("^# source-sha256: ([0-9a-f]{64})$", Pattern.MULTILINE);
    private static final Pattern COMMIT = Pattern.compile("^# source-commit: ([0-9a-f]{40})$", Pattern.MULTILINE);

    /** Upstream operations called by ClosetManagementClient users. Register new calls here. */
    private static final Set<String> USED = Set.of(
            "GET /presets", "PATCH /presets/{id}", "DELETE /presets/{id}",
            "GET /presets/{id}/image", "POST /presets/batch", "GET /presets/coverage",
            "POST /presets/source-sessions", "DELETE /presets/source-sessions/{sessionId}",
            "GET /presets/source-sessions/{sessionId}/garments",
            "GET /presets/source-sessions/{sessionId}/garments/{garmentId}/image",
            "POST /presets/source-sessions/{sessionId}/import",
            "GET /looks/categories", "POST /looks/categories", "PATCH /looks/categories/{key}",
            "DELETE /looks/categories/{key}", "GET /looks", "POST /looks", "PUT /looks/{id}/image",
            "POST /looks/compose", "GET /looks/{id}/image", "PATCH /looks/{id}", "DELETE /looks/{id}",
            "POST /looks/batch",
            "GET /poses", "POST /poses", "PATCH /poses/{id}", "DELETE /poses/{id}", "POST /poses/batch",
            "POST /poses/extract", "POST /poses/check", "GET /poses/check-settings", "PUT /poses/check-settings",
            "GET /poses/sensitive-terms", "POST /poses/sensitive-terms", "PATCH /poses/sensitive-terms/{id}", "DELETE /poses/sensitive-terms/{id}",
            "GET /feedback", "PATCH /feedback/{id}", "DELETE /feedback/{id}", "GET /feedback/settings", "PUT /feedback/settings",
            "GET /models", "GET /models/usage", "POST /models/providers", "PATCH /models/providers/{id}", "DELETE /models/providers/{id}",
            "POST /models/providers/{id}/probe", "POST /models/entries", "PATCH /models/entries/{id}",
            "DELETE /models/entries/{id}", "POST /models/entries/{id}/resume", "POST /models/entries/{id}/test",
            "PUT /models/entries/{id}/references", "PUT /models/entries/{id}/timeout",
            "PUT /models/routing/{capability}", "DELETE /models/routing/{capability}",
            "GET /onboarding/style-covers", "GET /onboarding/style-covers/{key}/image",
            "PUT /onboarding/style-covers/{key}/image", "DELETE /onboarding/style-covers/{key}/image",
            "GET /creations/showcases", "POST /creations/showcases", "POST /creations/showcases/uploads",
            "PUT /creations/showcases/{id}", "POST /creations/showcases/{id}/publish",
            "POST /creations/showcases/{id}/retire", "GET /creations/showcases/{id}/image",
            "GET /creations/templates", "POST /creations/templates/{id}/versions",
            "GET /creations/templates/{id}/versions/{versionId}", "PUT /creations/templates/{id}/versions/{versionId}",
            "POST /creations/templates/{id}/versions/{versionId}/verify", "POST /creations/templates/{id}/versions/{versionId}/publish",
            "POST /creations/templates/{id}/retire",
            "GET /creations/generation-options", "GET /creations/usage",
            "GET /creations/templates/{id}/versions/{versionId}/preview-image", "GET /creations/prompt-samples", "POST /creations/templates", "DELETE /creations/templates/{id}",
            "PUT /creations/templates/order", "GET /creations/trial-garments", "GET /creations/trial-garments/{id}/image",
            "GET /creations/trial-persons", "POST /creations/trial-persons",
            "GET /creations/trial-persons/{id}/image", "DELETE /creations/trial-persons/{id}",
            "POST /creations/templates/{id}/versions/{versionId}/trials", "GET /creations/templates/{id}/trials",
            "GET /creations/trials/{trialId}", "GET /creations/trials/{trialId}/image",
            "POST /creations/templates/{id}/versions/{versionId}/preview",
            "POST /creations/templates/{id}/versions/{versionId}/launch",
            "GET /users", "POST /users", "GET /users/{username}",
            "PATCH /users/{username}/status", "PATCH /users/{username}/role", "PATCH /users/{username}/profile",
            "POST /users/{username}/password-reset",
            "GET /users/{username}/sessions", "DELETE /users/{username}/sessions",
            "DELETE /users/{username}/sessions/{sessionRef}",
            "GET /users/{username}/login-lock", "DELETE /users/{username}/login-lock",
            "GET /users/{username}/events",
            "GET /users/{username}/portable-links", "DELETE /users/{username}/portable-links/{linkId}",
            "GET /users/{username}/quota", "PUT /users/{username}/quota",
            "GET /users/{username}/token-budget", "PUT /users/{username}/token-budget",
            "GET /registration", "PUT /registration",
            "GET /invites", "POST /invites", "GET /invites/{id}", "PATCH /invites/{id}", "DELETE /invites/{id}",
            "GET /account-events", "GET /security-audit", "GET /security-audit/integrity",
            "GET /insights/overview", "GET /insights/wardrobe", "GET /insights/usage", "GET /insights/trends",
            "GET /insights/recommendations", "GET /insights/ai",
            "GET /ops/status", "GET /ops/mail", "GET /ops/monitor", "GET /ops/assistant-token-budget", "GET /ops/tasks", "GET /ops/operation-log",
            "GET /dashboard/snapshot", "GET /exports/daily", "GET /exports/events",
            "GET /templates", "PATCH /templates/{id}",
            "GET /samples", "GET /samples/stats", "POST /samples", "GET /samples/{id}", "GET /samples/{id}/image",
            "PUT /samples/{id}", "POST /samples/{id}/extract", "POST /samples/{id}/review",
            "POST /samples/{id}/template-draft", "DELETE /samples/{id}",
            "GET /style-terms/pending", "POST /style-terms/pending/approve", "POST /style-terms/pending/reject",
            "GET /billing/settings",
            "PUT /billing/settings",
            "GET /billing/tiers",
            "PUT /billing/tiers/{tier}",
            "GET /billing/cost-rates",
            "POST /billing/cost-rates",
            "GET /billing/margin",
            "GET /billing/margin/alerts",
            "GET /billing/margin/explain",
            "GET /billing/products",
            "POST /billing/products",
            "PUT /billing/products/{id}",
            "GET /billing/orders",
            "POST /billing/orders/{id}/sandbox-dispute",
            "GET /billing/redemption-batches",
            "POST /billing/redemption-batches",
            "GET /billing/redemption-batches/{id}/codes",
            "POST /billing/redemption-batches/{id}/disable",
            "DELETE /billing/entitlements/{id}",
            "GET /users/{username}/billing",
            "POST /users/{username}/points",
            "POST /users/{username}/entitlements",
            "GET /tryon/settings",
            "PUT /tryon/settings",
            "POST /tryon/settings/reset",
            "GET /tryon/users/{username}/policy",
            "PUT /tryon/users/{username}/policy",
            "GET /tryon/users/{username}/usage",
            "POST /tryon/users/{username}/startup-failures/reset",
            "GET /tryon/accounts",
            "POST /tryon/accounts",
            "GET /tryon/accounts/{id}",
            "PATCH /tryon/accounts/{id}",
            "POST /tryon/accounts/{id}/credentials",
            "DELETE /tryon/accounts/{id}/credentials/{credentialId}",
            "POST /tryon/accounts/{id}/probe",
            "POST /tryon/accounts/{id}/budget",
            "GET /tryon/pool",
            "GET /tryon/reference-source",
            "GET /tryon/usage",
            "GET /tryon/sessions",
            "GET /tryon/sessions/{id}",
            "POST /tryon/sessions/{id}/stop",
            "POST /tryon/accounts/{id}/stop-sessions");

    // These operations remain upstream, but the retired admin capability page no longer uses them.
    static final Set<String> UPSTREAM_ONLY = Set.of("GET /creations/capabilities", "PUT /creations/capabilities");

    @Test void snapshotBodyMatchesRecordedHash() throws Exception {
        byte[] all = Files.readAllBytes(SNAPSHOT);
        String header = header(all);
        assertThat(COMMIT.matcher(header).find()).as("header records a committed source revision").isTrue();
        Matcher sha = SHA.matcher(header);
        assertThat(sha.find()).as("header records source-sha256").isTrue();
        assertThat(sha256(body(all))).as("snapshot edited by hand; rerun scripts/sync-closet-contract.sh")
                .isEqualTo(sha.group(1));
    }

    @Test void everyCalledOperationExistsInSnapshot() throws Exception {
        Set<String> contract = operations(new String(body(Files.readAllBytes(SNAPSHOT)), StandardCharsets.UTF_8));
        assertThat(contract).as("upstream operations used by the admin service or explicitly upstream-only")
                .containsExactlyInAnyOrderElementsOf(java.util.stream.Stream.concat(USED.stream(), UPSTREAM_ONLY.stream()).toList());
    }

    @Test void snapshotEqualsSourceWhenAvailable() throws Exception {
        String configured = System.getProperty("CLOSET_CONTRACT_SOURCE", System.getenv("CLOSET_CONTRACT_SOURCE"));
        Path source = configured != null ? Path.of(configured) : Path.of("../smart-closet-server/docs/reference/management-api.yaml");
        assumeTrue(Files.isRegularFile(source), "closet contract source not available: " + source);
        byte[] expected = Files.readAllBytes(source);
        assertThat(Arrays.equals(body(Files.readAllBytes(SNAPSHOT)), expected))
                .as("snapshot differs from %s; rerun scripts/sync-closet-contract.sh", source).isTrue();
    }

    private static String header(byte[] all) {
        String text = new String(all, StandardCharsets.UTF_8);
        int end = 0;
        for (int i = 0; i < HEADER_LINES; i++) end = text.indexOf('\n', end) + 1;
        return text.substring(0, end);
    }

    private static byte[] body(byte[] all) {
        int offset = 0;
        for (int i = 0; i < HEADER_LINES; i++) {
            while (all[offset] != '\n') offset++;
            offset++;
        }
        return Arrays.copyOfRange(all, offset, all.length);
    }

    /** "METHOD /path" for each operation under the top-level paths: block (two-space path, four-space method). */
    static Set<String> operations(String yaml) {
        Set<String> result = new TreeSet<>();
        boolean inPaths = false;
        String path = null;
        for (String line : yaml.split("\n")) {
            if (line.equals("paths:")) { inPaths = true; continue; }
            if (!inPaths) continue;
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) break;
            Matcher p = Pattern.compile("^  (/\\S*):\\s*$").matcher(line);
            if (p.matches()) { path = p.group(1); continue; }
            Matcher m = Pattern.compile("^    (get|put|post|patch|delete):(?:\\s.*)?$").matcher(line);
            if (m.matches() && path != null) result.add(m.group(1).toUpperCase() + " " + path);
        }
        return result;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
