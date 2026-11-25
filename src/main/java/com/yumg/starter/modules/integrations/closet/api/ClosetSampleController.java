package com.yumg.starter.modules.integrations.closet.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.application.ClosetTryContentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.LinkedHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/**
 * Outfit samples (management API 1.11). Extraction, review and the minor/identity rules stay in Fish Closet; the
 * gateway allowlists parameters and identifiers, forwards the multipart upload as the signed raw body, and never
 * audits image bytes or extraction text.
 */
@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet/samples")
public class ClosetSampleController {
    static final String SAMPLE = "ClosetSample";
    static final String ROOT = "/samples";
    static final String ID = "[A-Za-z0-9-]{1,64}";
    private final ClosetTryContentService service;
    public ClosetSampleController(ClosetTryContentService service) { this.service = service; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Review(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Draft(@Pattern(regexp = "CLASSIC|TREND") String kind, @Size(max = 60) String text) {}

    @GetMapping @PreAuthorize("hasAuthority('closet:samples:read')")
    public JsonNode list(@RequestParam(required = false) @Pattern(regexp = "PENDING|EXTRACTED|FAILED|APPROVED|REJECTED") String status,
                         @RequestParam(defaultValue = "200") int limit) {
        if (limit < 1 || limit > 500) throw ApiException.invalidParameter();
        var filters = new LinkedHashMap<String, String>();
        filters.put("status", status);
        filters.put("limit", Integer.toString(limit));
        return service.read(ROOT, filters);
    }

    @GetMapping("/stats") @PreAuthorize("hasAuthority('closet:samples:read')")
    public JsonNode stats(@RequestParam(required = false) @Pattern(regexp = "[A-Za-z_]{1,24}") String presentation) {
        var filters = new LinkedHashMap<String, String>();
        filters.put("presentation", presentation);
        return service.read(ROOT + "/stats", filters);
    }

    @PostMapping @PreAuthorize("hasAuthority('closet:samples:write')")
    public ResponseEntity<JsonNode> upload(HttpServletRequest request) throws IOException {
        MediaType type;
        try { type = MediaType.parseMediaType(request.getContentType()); }
        catch (RuntimeException exception) { throw ApiException.invalidParameter(); }
        String boundary = type.getParameter("boundary");
        if (!"multipart".equals(type.getType()) || !"form-data".equals(type.getSubtype()) || boundary == null
                || !ClosetShowcaseController.validBoundary(boundary)) throw ApiException.invalidParameter();
        int limit = ClosetTryContentService.IMAGE_LIMIT + 1024 * 1024;
        if (request.getContentLengthLong() > limit) throw ClosetShowcaseController.tooLarge();
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw ClosetShowcaseController.tooLarge();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.postImage(SAMPLE, "SAMPLE_UPLOAD", "new", ROOT, bytes, type));
    }

    @GetMapping("/{id}") @PreAuthorize("hasAuthority('closet:samples:read')")
    public JsonNode get(@PathVariable @Pattern(regexp = ID) String id) { return service.read(ROOT + "/" + id, java.util.Map.of()); }

    @GetMapping("/{id}/image") @PreAuthorize("hasAuthority('closet:samples:read')")
    public ResponseEntity<byte[]> image(@PathVariable @Pattern(regexp = ID) String id) {
        return ClosetRawImages.response(service.image(ROOT + "/" + id + "/image"));
    }

    @PutMapping("/{id}") @PreAuthorize("hasAuthority('closet:samples:write')")
    public JsonNode edit(@PathVariable @Pattern(regexp = ID) String id, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) throw ApiException.invalidParameter();
        return service.put(SAMPLE, "SAMPLE_EDIT", id, null, ROOT + "/" + id, body);
    }

    @PostMapping("/{id}/extract") @PreAuthorize("hasAuthority('closet:samples:write')")
    public JsonNode extract(@PathVariable @Pattern(regexp = ID) String id) {
        return service.post(SAMPLE, "SAMPLE_EXTRACT", id, null, ROOT + "/" + id + "/extract", null);
    }

    @PostMapping("/{id}/review") @PreAuthorize("hasAuthority('closet:samples:write')")
    public JsonNode review(@PathVariable @Pattern(regexp = ID) String id, @Valid @RequestBody Review body) {
        return service.post(SAMPLE, "SAMPLE_REVIEW", id, body.decision(), ROOT + "/" + id + "/review", body);
    }

    @PostMapping("/{id}/template-draft") @PreAuthorize("hasAuthority('closet:samples:write')")
    public JsonNode templateDraft(@PathVariable @Pattern(regexp = ID) String id, @Valid @RequestBody Draft body) {
        return service.post(SAMPLE, "SAMPLE_TEMPLATE_DRAFT", id, null, ROOT + "/" + id + "/template-draft", body);
    }

    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('closet:samples:write')")
    public ResponseEntity<Void> remove(@PathVariable @Pattern(regexp = ID) String id) {
        service.delete(SAMPLE, "SAMPLE_REMOVE", id, ROOT + "/" + id);
        return ResponseEntity.noContent().build();
    }
}
