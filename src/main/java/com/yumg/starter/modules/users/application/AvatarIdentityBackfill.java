package com.yumg.starter.modules.users.application;

import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class AvatarIdentityBackfill implements ApplicationRunner {
    private final UserRepository users;
    public AvatarIdentityBackfill(UserRepository users) { this.users = users; }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        users.findAllByAvatarPublicIdIsNullOrAvatarSeedIsNull()
                .forEach(user -> user.initializeAvatarIdentity(user.getId()));
    }
}
