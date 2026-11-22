package com.yumg.starter.modules.integrations.closet.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.application.ClosetTryContentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import static com.yumg.starter.modules.integrations.closet.application.ClosetTryContentService.CATEGORIES;
import static com.yumg.starter.modules.integrations.closet.application.ClosetTryContentService.IMAGE_LIMIT;
import static com.yumg.starter.modules.integrations.closet.application.ClosetTryContentService.LOOK;
import static com.yumg.starter.modules.integrations.closet.application.ClosetTryContentService.LOOKS;

/**
 * Curated looks for live try-on (design 2026-11-22, section 3.5). Exact length and state rules belong to Fish
 * Closet; the gateway only allowlists fields, enums and identifiers and keeps generous size ceilings.
 */
@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet")
public class ClosetLookController {
    static final String KEY = "[a-z][a-z0-9-]{1,23}";
    static final String LINE = "[^\\r\\n]*";
    private final ClosetTryContentService service;
    public ClosetLookController(ClosetTryContentService service) { this.service = service; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CategoryCreate(@NotBlank @Pattern(regexp = KEY) String key,
                                 @NotBlank @Size(max = 32) @Pattern(regexp = LINE) String name,
                                 @Min(-1_000_000) @Max(1_000_000) Integer sort) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CategoryEdit(@Size(min = 1, max = 32) @Pattern(regexp = LINE) String name,
                               @Min(-1_000_000) @Max(1_000_000) Integer sort, Boolean enabled) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LookCreate(@NotBlank @Size(max = 64) @Pattern(regexp = LINE) String name,
                             @NotBlank @Pattern(regexp = KEY) String category,
                             @Size(max = 8) List<@NotBlank @Size(max = 32) @Pattern(regexp = LINE) String> tags,
                             @NotBlank @Pattern(regexp = "FULL|UPPER|LOWER") String coverage,
                             @Pattern(regexp = "FEMALE|MALE|UNISEX") String gender) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LookCompose(@NotBlank @Size(max = 64) @Pattern(regexp = LINE) String name,
                              @NotBlank @Pattern(regexp = KEY) String category,
                              @Size(max = 8) List<@NotBlank @Size(max = 32) @Pattern(regexp = LINE) String> tags,
                              @NotBlank @Pattern(regexp = "FULL|UPPER|LOWER") String coverage,
                              @Pattern(regexp = "FEMALE|MALE|UNISEX") String gender,
                              @NotNull @Size(min = 2, max = 6) List<@NotNull UUID> presetIds) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LookEdit(@Size(min = 1, max = 64) @Pattern(regexp = LINE) String name,
                           @Pattern(regexp = KEY) String category,
                           @Size(max = 8) List<@NotBlank @Size(max = 32) @Pattern(regexp = LINE) String> tags,
                           @Pattern(regexp = "FULL|UPPER|LOWER") String coverage,
                           @Pattern(regexp = "FEMALE|MALE|UNISEX") String gender,
                           @Pattern(regexp = "DRAFT|LISTED|UNLISTED") String status,
                           @Min(-1_000_000) @Max(1_000_000) Integer sort, Boolean verified) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Batch(@NotNull @Size(min = 1, max = 200) List<@NotNull UUID> ids,
                        @NotBlank @Pattern(regexp = "LIST|UNLIST|REMOVE|VERIFY|UNVERIFY") String action) {}

    @GetMapping("/looks/categories") @PreAuthorize("hasAuthority('closet:looks:read')")
    public JsonNode categories() { return service.read(CATEGORIES, java.util.Map.of()); }

    @PostMapping("/looks/categories") @PreAuthorize("hasAuthority('closet:looks:write')")
    public ResponseEntity<JsonNode> createCategory(@Valid @RequestBody CategoryCreate body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.post(LOOK, "LOOK_CATEGORY_CREATE", body.key(), null, CATEGORIES, body));
    }

    @PatchMapping("/looks/categories/{key}") @PreAuthorize("hasAuthority('closet:looks:write')")
    public JsonNode editCategory(@PathVariable String key, @Valid @RequestBody CategoryEdit body) {
        return service.patch(LOOK, "LOOK_CATEGORY_EDIT", key, category(key), body);
    }

    @DeleteMapping("/looks/categories/{key}") @PreAuthorize("hasAuthority('closet:looks:write')")
    public ResponseEntity<Void> removeCategory(@PathVariable String key) {
        service.delete(LOOK, "LOOK_CATEGORY_REMOVE", key, category(key));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/looks") @PreAuthorize("hasAuthority('closet:looks:read')")
    public JsonNode list(@RequestParam(required = false) @Pattern(regexp = "DRAFT|LISTED|UNLISTED") String status,
                         @RequestParam(required = false) @Pattern(regexp = KEY) String category,
                         @RequestParam(required = false) Boolean verified,
                         @RequestParam(required = false) @Size(max = 64) String q,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "48") int size) {
        var filters = new LinkedHashMap<String, String>();
        filters.put("status", status);
        filters.put("category", category);
        filters.put("verified", verified == null ? null : verified.toString());
        filters.put("q", q);
        return service.page(LOOKS, page, size, filters);
    }

    @PostMapping("/looks") @PreAuthorize("hasAuthority('closet:looks:write')")
    public ResponseEntity<JsonNode> create(@Valid @RequestBody LookCreate body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.post(LOOK, "LOOK_CREATE", body.category(), null, LOOKS, body));
    }

    @PostMapping("/looks/compose") @PreAuthorize("hasAuthority('closet:looks:write')")
    public ResponseEntity<JsonNode> compose(@Valid @RequestBody LookCompose body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.post(LOOK, "LOOK_COMPOSE", body.category(), null, LOOKS + "/compose", body));
    }

    @PostMapping("/looks/batch") @PreAuthorize("hasAuthority('closet:looks:write')")
    public JsonNode batch(@Valid @RequestBody Batch body) {
        return service.post(LOOK, "LOOK_BATCH", "looks", body.action() + " " + body.ids().size(), LOOKS + "/batch", body);
    }

    @GetMapping("/looks/{id}/image") @PreAuthorize("hasAuthority('closet:looks:read')")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        return ClosetRawImages.response(service.image(look(id) + "/image"));
    }

    @PutMapping("/looks/{id}/image") @PreAuthorize("hasAuthority('closet:looks:write')")
    public JsonNode upload(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        var upload = ClosetRawImages.read(request, IMAGE_LIMIT);
        return service.putImage(LOOK, "LOOK_IMAGE_UPLOAD", id.toString(), look(id) + "/image", upload.bytes(), upload.type());
    }

    @PatchMapping("/looks/{id}") @PreAuthorize("hasAuthority('closet:looks:write')")
    public JsonNode edit(@PathVariable UUID id, @Valid @RequestBody LookEdit body) {
        return service.patch(LOOK, "LOOK_EDIT", id.toString(), look(id), body);
    }

    @DeleteMapping("/looks/{id}") @PreAuthorize("hasAuthority('closet:looks:write')")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        service.delete(LOOK, "LOOK_REMOVE", id.toString(), look(id));
        return ResponseEntity.noContent().build();
    }

    private static String look(UUID id) { return LOOKS + "/" + id; }

    private static String category(String key) {
        if (key == null || !key.matches(KEY)) throw ApiException.invalidParameter();
        return CATEGORIES + "/" + key;
    }
}
