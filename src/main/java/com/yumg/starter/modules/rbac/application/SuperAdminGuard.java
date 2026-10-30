package com.yumg.starter.modules.rbac.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.User;
import com.yumg.starter.entities.UserStatus;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Keeps at least one usable super administrator. Demotion and deactivation paths call this inside
 * their transaction before mutating; it row-locks every SUPER_ADMIN member so two concurrent
 * demotions of different administrators are serialized instead of both passing a stale count.
 */
@Component
public class SuperAdminGuard {
    public static final String ROLE = "SUPER_ADMIN";
    private final UserRepository users;

    public SuperAdminGuard(UserRepository users) {
        this.users = users;
    }

    /** Rejects removing or deactivating {@code target} when it is the last (active) super administrator. */
    public void requireAnotherSuperAdmin(User target) {
        if (!target.isSuperAdmin()) return;
        List<User> others = users.lockAllByRoleCode(ROLE).stream()
                .filter(member -> !member.getId().equals(target.getId())).toList();
        boolean lastMember = others.isEmpty();
        boolean lastActive = target.getStatus() == UserStatus.ACTIVE
                && others.stream().noneMatch(member -> member.getStatus() == UserStatus.ACTIVE);
        if (lastMember || lastActive) throw ApiException.lastSuperAdminProtected();
    }
}
