package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.application.ClosetPresetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import static com.yumg.starter.modules.integrations.closet.application.ClosetPresetService.COVERS;
import static com.yumg.starter.modules.integrations.closet.application.ClosetPresetService.PRESETS;

@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet")
public class ClosetPresetController {
    private static final int MAX_COVER_BYTES = 8 * 1024 * 1024;
    private final ClosetPresetService service;
    public ClosetPresetController(ClosetPresetService service) { this.service = service; }

    public record Edit(String name, String status, String gender, List<String> styles, List<String> seasons, Integer sort) {}
    public record Batch(List<UUID> ids, String action) {}
    public record Credentials(@NotBlank @Size(max=80) @Pattern(regexp="[^\\r\\n]*") String username, String password) {}
    public record Import(List<UUID> garmentIds, boolean list) {}

    @GetMapping("/presets") @PreAuthorize("hasAuthority('closet:presets:read')")
    public JsonNode list(@RequestParam(required=false) String status, @RequestParam(required=false) String gender,
            @RequestParam(required=false) String group, @RequestParam(required=false) String q,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="48") int size) {
        var filters = new LinkedHashMap<String,String>();
        filters.put("status", status);
        filters.put("gender", gender);
        filters.put("group", group);
        filters.put("q", q);
        return service.list(PRESETS, page, size, filters);
    }

    @GetMapping("/presets/{id}/image") @PreAuthorize("hasAuthority('closet:presets:read')")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        return card(PRESETS + "/" + id + "/image");
    }

    @PatchMapping("/presets/{id}") @PreAuthorize("hasAuthority('closet:presets:write')")
    public JsonNode edit(@PathVariable UUID id, @RequestBody Edit edit) { return service.patch(PRESETS + "/" + id, edit, id.toString()); }

    @DeleteMapping("/presets/{id}") @PreAuthorize("hasAuthority('closet:presets:write')")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        service.delete(PRESETS + "/" + id, "PRESET_REMOVE", id.toString());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/presets/batch") @PreAuthorize("hasAuthority('closet:presets:write')")
    public JsonNode batch(@RequestBody Batch data) { return service.post(PRESETS + "/batch", data, "PRESET_BATCH", "library", null); }

    @GetMapping("/presets/coverage") @PreAuthorize("hasAuthority('closet:presets:read')")
    public JsonNode coverage() { return service.read(PRESETS + "/coverage"); }

    @PostMapping("/presets/source-sessions") @PreAuthorize("hasAuthority('closet:presets:write')")
    public ResponseEntity<JsonNode> open(@Valid @RequestBody Credentials data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.post(PRESETS + "/source-sessions", data, "PRESET_SOURCE_ACCESS", data.username(), "验证管理账号 " + data.username()));
    }

    @DeleteMapping("/presets/source-sessions/{sessionId}") @PreAuthorize("hasAuthority('closet:presets:write')")
    public ResponseEntity<Void> close(@PathVariable String sessionId) {
        service.delete(session(sessionId), "PRESET_SOURCE_CLOSE", "source");
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/presets/source-sessions/{sessionId}/garments") @PreAuthorize("hasAuthority('closet:presets:write')")
    public ResponseEntity<JsonNode> sources(@PathVariable String sessionId, @RequestParam(required=false) String group,
            @RequestParam(required=false) String q, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="48") int size) {
        var filters = new LinkedHashMap<String,String>();
        filters.put("group", group);
        filters.put("q", q);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(session(sessionId) + "/garments",page,size,filters));
    }

    @GetMapping("/presets/source-sessions/{sessionId}/garments/{garmentId}/image") @PreAuthorize("hasAuthority('closet:presets:write')")
    public ResponseEntity<byte[]> sourceImage(@PathVariable String sessionId, @PathVariable UUID garmentId) {
        return card(session(sessionId) + "/garments/" + garmentId + "/image");
    }

    @PostMapping("/presets/source-sessions/{sessionId}/import") @PreAuthorize("hasAuthority('closet:presets:write')")
    public JsonNode add(@PathVariable String sessionId, @RequestBody Import request) {
        return service.post(session(sessionId) + "/import", request, "PRESET_SOURCE_IMPORT", "source", null);
    }

    @GetMapping("/onboarding/style-covers") @PreAuthorize("hasAuthority('closet:onboarding:read')")
    public JsonNode covers() { return service.read(COVERS); }

    @GetMapping("/onboarding/style-covers/{key}/image") @PreAuthorize("hasAuthority('closet:onboarding:read')")
    public ResponseEntity<byte[]> coverImage(@PathVariable String key) { return image(cover(key), MediaType.IMAGE_JPEG); }

    @PutMapping("/onboarding/style-covers/{key}/image") @PreAuthorize("hasAuthority('closet:onboarding:write')")
    public JsonNode upload(@PathVariable String key, HttpServletRequest request) throws IOException {
        String raw = request.getContentType();
        if (raw == null || !Set.of("image/jpeg","image/png","image/webp").contains(raw)) throw ApiException.invalidParameter();
        if (request.getContentLengthLong() > MAX_COVER_BYTES) throw ClosetShowcaseController.tooLarge();
        byte[] bytes = request.getInputStream().readNBytes(MAX_COVER_BYTES + 1);
        if (bytes.length > MAX_COVER_BYTES) throw ClosetShowcaseController.tooLarge();
        return service.upload(cover(key), key, bytes, MediaType.parseMediaType(raw));
    }

    @DeleteMapping("/onboarding/style-covers/{key}/image") @PreAuthorize("hasAuthority('closet:onboarding:write')")
    public ResponseEntity<Void> deleteCover(@PathVariable String key) {
        service.delete(cover(key), "ONBOARDING_COVER_REMOVE", key);
        return ResponseEntity.noContent().build();
    }

    /** Card thumbnails (PNG or JPEG) of immutable images: labelled by signature, privately cacheable for a day. */
    private ResponseEntity<byte[]> card(String path) {
        byte[] bytes = service.image(path);
        return ResponseEntity.ok().contentType(ClosetRawImages.sniff(bytes)).cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    private ResponseEntity<byte[]> image(String path, MediaType type) {
        byte[] bytes = service.image(path);
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    private static String session(String id) {
        if (!id.matches("[A-Za-z0-9_-]{43}")) throw ApiException.invalidParameter();
        return PRESETS + "/source-sessions/" + id;
    }

    private static String cover(String key) {
        if (!key.matches("[a-z]{1,20}")) throw ApiException.invalidParameter();
        return COVERS + "/" + key + "/image";
    }
}
