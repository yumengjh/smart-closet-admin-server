package com.yumg.starter.modules.users.api;

import com.yumg.starter.common.web.PublicApi;
import com.yumg.starter.modules.users.application.AvatarContent;
import com.yumg.starter.modules.users.application.UserAvatarManagementService;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/avatar-candidates")
public class AvatarCandidateController {
    private final UserAvatarManagementService avatars;
    public AvatarCandidateController(UserAvatarManagementService avatars) { this.avatars = avatars; }

    @GetMapping("/{candidateId}")
    @PublicApi(minimalResponse = true)
    public ResponseEntity<byte[]> read(
            @PathVariable @Pattern(regexp = "[0-9a-fA-F-]{36}") String candidateId) {
        AvatarContent content = avatars.readCandidate(candidateId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                .eTag('"' + content.etag() + '"')
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
                .body(content.bytes());
    }
}
