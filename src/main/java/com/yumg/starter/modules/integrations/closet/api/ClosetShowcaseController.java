package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.common.api.ApiErrorCode;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.application.ClosetShowcaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet/creations/showcases")
public class ClosetShowcaseController {
    public static final int UPLOAD_LIMIT = 12 * 1024 * 1024;
    private final ClosetShowcaseService service;
    public ClosetShowcaseController(ClosetShowcaseService service) { this.service = service; }
    public record Create(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,80}") String uploadId,
                         @NotBlank @Size(max = 80) @Pattern(regexp = "[^\\r\\n]*") String title,
                         @NotNull @Size(max = 500) String description, @NotNull @Min(0) @Max(10000) Integer sortOrder) {}
    public record Edit(@NotNull @Min(0) Long revision,
                       @NotBlank @Size(max = 80) @Pattern(regexp = "[^\\r\\n]*") String title,
                       @NotNull @Size(max = 500) String description, @NotNull @Min(0) @Max(10000) Integer sortOrder) {}
    public record Revision(@NotNull @Min(0) Long revision) {}

    @GetMapping @PreAuthorize("hasAuthority('closet:showcase:read')")
    public ResponseEntity<JsonNode> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || page >= 1000000 || size < 1 || size > 50) throw ApiException.invalidParameter();
        JsonNode result = service.list(page, size);
        // Gateway pages are zero-based; source pages are one-based.
        if (result instanceof tools.jackson.databind.node.ObjectNode object) object.put("page", page);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @PostMapping("/uploads") @PreAuthorize("hasAuthority('closet:showcase:write')")
    public JsonNode upload(HttpServletRequest request) throws IOException {
        MediaType type;
        try { type = MediaType.parseMediaType(request.getContentType()); }
        catch (RuntimeException exception) { throw ApiException.invalidParameter(); }
        String boundary = type.getParameter("boundary");
        if (!"multipart".equals(type.getType()) || !"form-data".equals(type.getSubtype()) || boundary == null
                || !validBoundary(boundary)) throw ApiException.invalidParameter();
        if (request.getContentLengthLong() > UPLOAD_LIMIT) throw tooLarge();
        byte[] bytes = request.getInputStream().readNBytes(UPLOAD_LIMIT + 1);
        if (bytes.length > UPLOAD_LIMIT) throw tooLarge();
        return service.upload(bytes, type);
    }
    @PostMapping @PreAuthorize("hasAuthority('closet:showcase:write')")
    public JsonNode create(@Valid @RequestBody Create body) { return service.write("CREATE", "", body, false); }
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('closet:showcase:write')")
    public JsonNode edit(@PathVariable String id, @Valid @RequestBody Edit body) { return service.write("EDIT", path(id), body, true); }
    @PostMapping("/{id}/publish") @PreAuthorize("hasAuthority('closet:showcase:publish')")
    public JsonNode publish(@PathVariable String id, @Valid @RequestBody Revision body) { return service.write("PUBLISH", path(id) + "/publish", body, false); }
    @PostMapping("/{id}/retire") @PreAuthorize("hasAuthority('closet:showcase:publish')")
    public JsonNode retire(@PathVariable String id, @Valid @RequestBody Revision body) { return service.write("RETIRE", path(id) + "/retire", body, false); }
    @GetMapping("/{id}/image") @PreAuthorize("hasAuthority('closet:showcase:read')")
    public ResponseEntity<StreamingResponseBody> image(@PathVariable String id) {
        path(id);
        byte[] image = service.image(id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(output -> output.write(image));
    }
    private static String path(String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw ApiException.invalidParameter();
        return "/" + id;
    }
    static boolean validBoundary(String boundary) {
        if (boundary.length() >= 2 && boundary.charAt(0) == '"' && boundary.charAt(boundary.length() - 1) == '"') {
            boundary = boundary.substring(1, boundary.length() - 1);
        } else if (boundary.startsWith("\"") || boundary.endsWith("\"")) {
            return false;
        }
        return !boundary.isEmpty() && boundary.length() <= 70 && boundary.charAt(boundary.length() - 1) != ' '
                && boundary.matches("[A-Za-z0-9'()+_,./:=? -]+");
    }
    static ApiException tooLarge() {
        return ApiException.fromUpstream(ApiErrorCode.CLOSET_REQUEST_TOO_LARGE, null, List.of());
    }
}
