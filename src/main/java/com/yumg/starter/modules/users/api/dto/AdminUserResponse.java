package com.yumg.starter.modules.users.api.dto;

import com.yumg.starter.entities.User;
import com.yumg.starter.entities.Role;
import java.util.List;

public record AdminUserResponse(String id, String username, String displayName, String status,
                                String avatarUrl, String avatarType, List<String> roles) {
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getStatus().name(), UserAvatarUrl.from(user), user.getAvatarType().name(),
                user.getRoles().stream().map(Role::getCode).sorted().toList());
    }
}
