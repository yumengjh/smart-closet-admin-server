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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import static com.yumg.starter.modules.integrations.closet.application.ClosetTryContentService.IMAGE_LIMIT;
import static com.yumg.starter.modules.integrations.closet.application.ClosetTryContentService.POSE;
import static com.yumg.starter.modules.integrations.closet.application.ClosetTryContentService.POSES;
import static com.yumg.starter.modules.integrations.closet.application.ClosetTryContentService.TERMS;

/**
 * Pose presets, the six sensitive-check switches and the sensitive word list (design 2026-11-22, sections 4.2, 5 and 5.3).
 * Photo extraction forwards the raw image once and keeps nothing; the draft comes back for the administrator to edit and save.
 */
@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet")
public class ClosetPoseController {
    static final String ID = "[a-z][a-z0-9-]{1,39}";
    static final String LINE = ClosetLookController.LINE;
    static final String KIND = "SUGGESTIVE|NUDITY|MINOR";
    private final ClosetTryContentService service;
    public ClosetPoseController(ClosetTryContentService service) { this.service = service; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoseCreate(@Pattern(regexp = ID) String id,
                             @NotBlank @Size(max = 48) @Pattern(regexp = LINE) String name,
                             @NotBlank @Size(max = 600) String description,
                             @Size(max = 8) List<@NotBlank @Size(max = 32) @Pattern(regexp = LINE) String> tags,
                             @Pattern(regexp = "[A-Z][A-Z_]{0,23}") String viewAngle,
                             JsonNode keypoints,
                             @Pattern(regexp = "DRAFT|LISTED|UNLISTED") String status) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoseEdit(@Size(min = 1, max = 48) @Pattern(regexp = LINE) String name,
                           @Size(min = 1, max = 600) String description,
                           @Size(max = 8) List<@NotBlank @Size(max = 32) @Pattern(regexp = LINE) String> tags,
                           @Pattern(regexp = "[A-Z][A-Z_]{0,23}") String viewAngle,
                           JsonNode keypoints,
                           @Pattern(regexp = "DRAFT|LISTED|UNLISTED") String status,
                           @Min(-1_000_000) @Max(1_000_000) Integer sort) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Batch(@NotNull @Size(min = 1, max = 200) List<@NotNull @Pattern(regexp = ID) String> ids,
                        @NotBlank @Pattern(regexp = "LIST|UNLIST|REMOVE") String action) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Check(@Size(max = 48) @Pattern(regexp = LINE) String name, @NotBlank @Size(max = 600) String description) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Flags(@NotNull Boolean suggestive, @NotNull Boolean nudity, @NotNull Boolean minor) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TermCreate(@NotBlank @Size(max = 20) @Pattern(regexp = LINE) String term,
                             @NotBlank @Pattern(regexp = KIND) String kind,
                             @NotBlank @Size(max = 60) @Pattern(regexp = LINE) String reason) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TermEdit(@Size(min = 1, max = 20) @Pattern(regexp = LINE) String term,
                           @Pattern(regexp = KIND) String kind,
                           @Size(min = 1, max = 60) @Pattern(regexp = LINE) String reason,
                           Boolean enabled) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckSettings(@NotNull @Valid Flags user, @NotNull @Valid Flags admin, @NotNull @Min(0) Long revision) {}

    @GetMapping("/poses") @PreAuthorize("hasAuthority('closet:poses:read')")
    public JsonNode list(@RequestParam(required = false) @Pattern(regexp = "DRAFT|LISTED|UNLISTED") String status,
                         @RequestParam(required = false) @Size(max = 64) String q) {
        var filters = new LinkedHashMap<String, String>();
        filters.put("status", status);
        filters.put("q", q);
        return service.read(POSES, filters);
    }

    @PostMapping("/poses") @PreAuthorize("hasAuthority('closet:poses:write')")
    public ResponseEntity<JsonNode> create(@Valid @RequestBody PoseCreate body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.post(POSE, "POSE_CREATE", body.id() == null ? "new" : body.id(), null, POSES, body));
    }

    @PatchMapping("/poses/{id}") @PreAuthorize("hasAuthority('closet:poses:write')")
    public JsonNode edit(@PathVariable String id, @Valid @RequestBody PoseEdit body) {
        return service.patch(POSE, "POSE_EDIT", id, pose(id), body);
    }

    @DeleteMapping("/poses/{id}") @PreAuthorize("hasAuthority('closet:poses:write')")
    public ResponseEntity<Void> remove(@PathVariable String id) {
        service.delete(POSE, "POSE_REMOVE", id, pose(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/poses/batch") @PreAuthorize("hasAuthority('closet:poses:write')")
    public JsonNode batch(@Valid @RequestBody Batch body) {
        return service.post(POSE, "POSE_BATCH", "poses", body.action() + " " + body.ids().size(), POSES + "/batch", body);
    }

    /** The draft is not stored anywhere; it is returned for the administrator to review and save via POST /poses. */
    @PostMapping("/poses/extract") @PreAuthorize("hasAuthority('closet:poses:write')")
    public ResponseEntity<JsonNode> extract(HttpServletRequest request) throws IOException {
        var upload = ClosetRawImages.read(request, IMAGE_LIMIT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.postImage(POSE, "POSE_EXTRACT", "photo", POSES + "/extract", upload.bytes(), upload.type()));
    }

    /** Text-only preview; not affected by the switches and has no side effects, so it is not audited. */
    @PostMapping("/poses/check") @PreAuthorize("hasAuthority('closet:poses:read')")
    public JsonNode check(@Valid @RequestBody Check body) {
        return service.query(POSES + "/check", body);
    }

    @GetMapping("/poses/check-settings") @PreAuthorize("hasAuthority('closet:poses:read')")
    public ResponseEntity<JsonNode> settings() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(POSES + "/check-settings", java.util.Map.of()));
    }

    @PutMapping("/poses/check-settings") @PreAuthorize("hasAuthority('closet:poses:write')")
    public JsonNode updateSettings(@Valid @RequestBody CheckSettings body) {
        String detail = "user[" + flags(body.user()) + "] admin[" + flags(body.admin()) + "] revision=" + body.revision();
        return service.put(POSE, "POSE_CHECK_SETTINGS", "check-settings", detail, POSES + "/check-settings", body);
    }

    @GetMapping("/poses/sensitive-terms") @PreAuthorize("hasAuthority('closet:poses:read')")
    public ResponseEntity<JsonNode> terms(@RequestParam(required = false) @Pattern(regexp = KIND) String kind,
                                          @RequestParam(required = false) Boolean enabled,
                                          @RequestParam(required = false) @Size(max = 64) String q) {
        var filters = new LinkedHashMap<String, String>();
        filters.put("kind", kind);
        filters.put("enabled", enabled == null ? null : enabled.toString());
        filters.put("q", q);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(TERMS, filters));
    }

    /** Audited with the kind only; the word itself stays out of the audit trail. */
    @PostMapping("/poses/sensitive-terms") @PreAuthorize("hasAuthority('closet:poses:write')")
    public ResponseEntity<JsonNode> createTerm(@Valid @RequestBody TermCreate body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.post(POSE, "POSE_TERM_CREATE", "sensitive-terms", "kind=" + body.kind(), TERMS, body));
    }

    @PatchMapping("/poses/sensitive-terms/{id}") @PreAuthorize("hasAuthority('closet:poses:write')")
    public JsonNode editTerm(@PathVariable UUID id, @Valid @RequestBody TermEdit body) {
        return service.patch(POSE, "POSE_TERM_EDIT", id.toString(), TERMS + "/" + id, body);
    }

    /** A real delete upstream (seed words included); it takes effect on the next check. */
    @DeleteMapping("/poses/sensitive-terms/{id}") @PreAuthorize("hasAuthority('closet:poses:write')")
    public ResponseEntity<Void> deleteTerm(@PathVariable UUID id) {
        service.delete(POSE, "POSE_TERM_DELETE", id.toString(), TERMS + "/" + id);
        return ResponseEntity.noContent().build();
    }

    private static String flags(Flags f) {
        return "suggestive=" + f.suggestive() + ",nudity=" + f.nudity() + ",minor=" + f.minor();
    }

    private static String pose(String id) {
        if (id == null || !id.matches(ID)) throw ApiException.invalidParameter();
        return POSES + "/" + id;
    }
}
