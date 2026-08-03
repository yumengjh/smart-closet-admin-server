package com.yumg.starter.modules.users.api.dto;

import com.yumg.starter.entities.User;
import java.util.List;

public record CurrentUserResponse(String id, String username, String displayName, String status,
                                  String avatarUrl, List<String> roles, List<String> permissions) {
    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getStatus().name(),
                UserAvatarUrl.from(user),
                user.getRoles().stream().map(role -> role.getCode()).sorted().toList(),
                user.getRoles().stream().flatMap(role -> role.getPermissions().stream())
                        .map(permission -> permission.getCode()).distinct().sorted().toList());
    }
}
