package com.yumg.starter.modules.users.api;

import com.yumg.starter.common.web.PublicApi;
import com.yumg.starter.modules.users.application.AvatarContent;
import com.yumg.starter.modules.users.application.UserAvatarService;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;

@RestController
@Validated
@RequestMapping("/api/v1/avatars")
public class UserAvatarController {
    private final UserAvatarService avatars;
    public UserAvatarController(UserAvatarService avatars) { this.avatars = avatars; }

    @GetMapping("/{publicId}")
    @PublicApi(minimalResponse = true)
    public ResponseEntity<byte[]> read(
            @PathVariable @Pattern(regexp = "[0-9a-fA-F-]{36}") String publicId) {
        AvatarContent content = avatars.read(publicId);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                .eTag('"' + content.etag() + '"')
                .cacheControl(content.fallback()
                        ? CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()
                        : CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff");
        if ("image/svg+xml".equals(content.contentType())) {
            response.header("Content-Security-Policy",
                    "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        }
        return response.body(content.bytes());
    }
}
