package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.modules.integrations.closet.application.ClosetCreationService;
import com.yumg.starter.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Explicit creation routes; no catch-all proxy is exposed. */
@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet/creations")
public class ClosetCreationController {
    private final ClosetCreationService service;
    public ClosetCreationController(ClosetCreationService service) { this.service = service; }

    @GetMapping("/templates") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode templates() { return service.read("/templates", Map.of()); }

    @GetMapping("/prompt-samples") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode promptSamples() { return service.read("/prompt-samples", Map.of()); }

    @PostMapping("/templates") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode createEffect(@RequestBody JsonNode body) { return service.write("EFFECT_CREATED", "POST", "/templates", body); }

    // Literal path: wins over /templates/{id} for PUT and never shares a method with DELETE /templates/{id}.
    @PutMapping("/templates/order") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode reorderEffects(@RequestBody JsonNode body) { return service.write("EFFECTS_REORDERED", "PUT", "/templates/order", body); }

    @DeleteMapping("/templates/{id}") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode deleteEffect(@PathVariable String id) { return service.delete("EFFECT_DELETED", template(id)); }

    @PostMapping("/templates/{id}/versions") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode create(@PathVariable String id, @RequestBody JsonNode body) {
        return service.write("VERSION_CREATED", "POST", template(id) + "/versions", body);
    }

    @GetMapping("/templates/{id}/versions/{versionId}") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode version(@PathVariable String id, @PathVariable String versionId) {
        return service.read(versionPath(id, versionId), Map.of());
    }

    @PutMapping("/templates/{id}/versions/{versionId}") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode update(@PathVariable String id, @PathVariable String versionId, @RequestBody JsonNode body) {
        return service.write("VERSION_UPDATED", "PUT", versionPath(id, versionId), body);
    }

    @PostMapping("/templates/{id}/versions/{versionId}/verify") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode verify(@PathVariable String id, @PathVariable String versionId, @RequestBody JsonNode body) {
        return service.write("VERSION_VERIFIED", "POST", versionPath(id, versionId) + "/verify", body);
    }

    @PostMapping("/templates/{id}/versions/{versionId}/publish") @PreAuthorize("hasAuthority('closet:creation:publish')")
    public JsonNode publish(@PathVariable String id, @PathVariable String versionId, @RequestBody JsonNode body) {
        return service.write("VERSION_PUBLISHED", "POST", versionPath(id, versionId) + "/publish", body);
    }

    @PostMapping("/templates/{id}/retire") @PreAuthorize("hasAuthority('closet:creation:publish')")
    public JsonNode retire(@PathVariable String id, @RequestBody JsonNode body) {
        return service.write("TEMPLATE_RETIRED", "POST", template(id) + "/retire", body);
    }

    @GetMapping("/capabilities") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode capabilities(@RequestParam org.springframework.util.MultiValueMap<String, String> query) {
        List<String> purposes = query.get("purpose");
        if (purposes == null) return service.read("/capabilities", Map.of());
        if (purposes.size() != 1 || !List.of("ART_CREATION", "OOTD", "BASELINE", "ALL").contains(purposes.get(0)))
            throw ApiException.invalidParameter();
        return service.read("/capabilities", Map.of("purpose", purposes));
    }

    @GetMapping("/generation-options") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode generationOptions() { return service.read("/generation-options", Map.of()); }

    @PutMapping("/capabilities") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode capabilities(@RequestBody JsonNode body) { return service.write("CAPABILITY_UPDATED", "PUT", "/capabilities", body); }

    @GetMapping("/usage") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode usage(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 30)
            throw com.yumg.starter.common.api.ApiException.invalidParameter();
        return service.read("/usage", Map.of("from", List.of(from.toString()), "to", List.of(to.toString())));
    }

    @GetMapping("/trial-garments") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode trialGarments() { return service.read("/trial-garments", Map.of()); }

    @GetMapping("/trial-garments/{id}/image") @PreAuthorize("hasAuthority('closet:creation:read')")
    public ResponseEntity<byte[]> trialGarmentImage(@PathVariable String id) {
        return image("/trial-garments/" + uuid(id) + "/image");
    }

    @GetMapping("/trial-persons") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode trialPersons() { return service.read("/trial-persons", Map.of()); }

    @PostMapping("/trial-persons") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode uploadTrialPerson(HttpServletRequest request) throws IOException {
        MediaType type;
        try { type = MediaType.parseMediaType(request.getContentType()); }
        catch (RuntimeException exception) { throw ApiException.invalidParameter(); }
        String boundary = type.getParameter("boundary");
        if (!"multipart".equals(type.getType()) || !"form-data".equals(type.getSubtype()) || boundary == null
                || !ClosetShowcaseController.validBoundary(boundary)) throw ApiException.invalidParameter();
        if (request.getContentLengthLong() > ClosetShowcaseController.UPLOAD_LIMIT) throw ClosetShowcaseController.tooLarge();
        byte[] bytes = request.getInputStream().readNBytes(ClosetShowcaseController.UPLOAD_LIMIT + 1);
        if (bytes.length > ClosetShowcaseController.UPLOAD_LIMIT) throw ClosetShowcaseController.tooLarge();
        return service.upload("TRIAL_PERSON_UPLOADED", "/trial-persons", bytes, type);
    }

    @GetMapping("/trial-persons/{id}/image") @PreAuthorize("hasAuthority('closet:creation:read')")
    public ResponseEntity<byte[]> trialPersonImage(@PathVariable String id) {
        return image("/trial-persons/" + uuid(id) + "/image");
    }

    @DeleteMapping("/trial-persons/{id}") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode deleteTrialPerson(@PathVariable String id) {
        return service.delete("TRIAL_PERSON_DELETED", "/trial-persons/" + uuid(id));
    }

    @PostMapping("/templates/{id}/versions/{versionId}/trials") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode startTrial(@PathVariable String id, @PathVariable String versionId, @RequestBody JsonNode body) {
        return service.write("TRIAL_STARTED", "POST", template(id) + "/versions/" + uuid(versionId) + "/trials", body);
    }

    @GetMapping("/templates/{id}/trials") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode trials(@PathVariable String id) { return service.read(template(id) + "/trials", Map.of()); }

    @GetMapping("/trials/{trialId}") @PreAuthorize("hasAuthority('closet:creation:read')")
    public JsonNode trial(@PathVariable String trialId) { return service.read("/trials/" + uuid(trialId), Map.of()); }

    @GetMapping("/trials/{trialId}/image") @PreAuthorize("hasAuthority('closet:creation:read')")
    public ResponseEntity<byte[]> trialImage(@PathVariable String trialId) {
        return image("/trials/" + uuid(trialId) + "/image");
    }

    @PostMapping("/templates/{id}/versions/{versionId}/preview") @PreAuthorize("hasAuthority('closet:creation:write')")
    public JsonNode preview(@PathVariable String id, @PathVariable String versionId, @RequestBody JsonNode body) {
        return service.write("PREVIEW_SET", "POST", template(id) + "/versions/" + uuid(versionId) + "/preview", body);
    }

    @PostMapping("/templates/{id}/versions/{versionId}/launch") @PreAuthorize("hasAuthority('closet:creation:publish')")
    public JsonNode launch(@PathVariable String id, @PathVariable String versionId, @RequestBody JsonNode body) {
        return service.write("VERSION_LAUNCHED", "POST", template(id) + "/versions/" + uuid(versionId) + "/launch", body);
    }

    @GetMapping("/templates/{id}/versions/{versionId}/preview-image") @PreAuthorize("hasAuthority('closet:creation:read')")
    public ResponseEntity<byte[]> previewImage(@PathVariable String id, @PathVariable String versionId) {
        return image(template(id) + "/versions/" + uuid(versionId) + "/preview-image");
    }

    private ResponseEntity<byte[]> image(String path) {
        byte[] image = service.image(path);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(image);
    }
    private static String uuid(String value) {
        if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw ApiException.invalidParameter();
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static String template(String id) {
        if (!id.matches("^[a-z0-9][a-z0-9-]{1,79}$"))
            throw com.yumg.starter.common.api.ApiException.invalidParameter();
        return "/templates/" + id;
    }
    private static String versionPath(String id, String versionId) {
        if (!versionId.matches("[A-Za-z0-9_-]{1,80}"))
            throw com.yumg.starter.common.api.ApiException.invalidParameter();
        return template(id) + "/versions/" + versionId;
    }
}
