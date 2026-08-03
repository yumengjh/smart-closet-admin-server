package com.yumg.starter.modules.users.api;

import com.yumg.starter.modules.users.api.dto.ChangePasswordRequest;
import com.yumg.starter.modules.users.api.dto.CurrentUserResponse;
import com.yumg.starter.modules.users.api.dto.UpdateProfileRequest;
import com.yumg.starter.modules.users.application.UserProfileService;
import com.yumg.starter.modules.users.application.UserAvatarManagementService;
import com.yumg.starter.modules.users.api.dto.AvatarCandidateResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import com.yumg.starter.modules.users.api.dto.SessionResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "当前用户") @SecurityRequirement(name = "bearerAuth")
public class UserProfileController {
    private final UserProfileService users;
    private final UserAvatarManagementService avatars;
    public UserProfileController(UserProfileService users, UserAvatarManagementService avatars) {
        this.users = users; this.avatars = avatars;
    }
    @GetMapping public CurrentUserResponse current(@AuthenticationPrincipal Jwt jwt) { return users.current(jwt.getSubject()); }
    @PatchMapping public CurrentUserResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) { return users.updateProfile(jwt.getSubject(), request); }
    @PutMapping("/password") public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) { users.changePassword(jwt.getSubject(), request); return ResponseEntity.noContent().build(); }
    @GetMapping("/sessions") public List<SessionResponse> sessions(@AuthenticationPrincipal Jwt jwt) { return users.sessions(jwt.getSubject()); }
    @PostMapping("/sessions/revoke") public ResponseEntity<Void> revokeAllSessions(@AuthenticationPrincipal Jwt jwt) { users.revokeAllSessions(jwt.getSubject()); return ResponseEntity.noContent().build(); }
    @PutMapping(value = "/avatar", consumes = "multipart/form-data")
    public CurrentUserResponse uploadAvatar(@AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > 5L * 1024 * 1024) throw com.yumg.starter.common.api.ApiException.avatarFileTooLarge();
        return avatars.upload(jwt.getSubject(), file.getBytes());
    }
    @DeleteMapping("/avatar")
    public CurrentUserResponse resetAvatar(@AuthenticationPrincipal Jwt jwt) {
        return avatars.reset(jwt.getSubject());
    }
    @PostMapping("/avatar/system-candidates")
    public AvatarCandidateResponse createAvatarCandidate(@AuthenticationPrincipal Jwt jwt) {
        return avatars.createCandidate(jwt.getSubject());
    }
    @PostMapping("/avatar/system-candidates/{candidateId}/apply")
    public CurrentUserResponse applyAvatarCandidate(@AuthenticationPrincipal Jwt jwt,
            @org.springframework.web.bind.annotation.PathVariable
            @Pattern(regexp = "[0-9a-fA-F-]{36}") String candidateId) {
        return avatars.applyCandidate(jwt.getSubject(), candidateId);
    }
}
