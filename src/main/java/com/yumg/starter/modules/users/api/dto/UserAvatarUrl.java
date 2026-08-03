package com.yumg.starter.modules.users.api.dto;

import com.yumg.starter.entities.User;

public final class UserAvatarUrl {
    private UserAvatarUrl() {}

    public static String from(User user) {
        return "/api/v1/avatars/" + user.getAvatarPublicId() + "?v=" + user.getAvatarRevision();
    }
}
