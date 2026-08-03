package com.yumg.starter.modules.users.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumg.starter.entities.Role;
import com.yumg.starter.entities.User;
import org.junit.jupiter.api.Test;

class AdminUserResponseTest {

    @Test
    void includesAssignedRolesSoAdminChangesAreVisibleInTheUserList() {
        User user = new User("alice", "Alice", "password-hash");
        user.grant(new Role("USER", "User"));
        user.grant(new Role("ADMIN", "Administrator"));

        AdminUserResponse response = AdminUserResponse.from(user);

        assertThat(response.roles()).containsExactly("ADMIN", "USER");
        assertThat(response.avatarUrl()).contains(user.getAvatarPublicId()).doesNotContain(user.getId());
        assertThat(response.avatarType()).isEqualTo("SYSTEM_INITIAL");
    }
}
