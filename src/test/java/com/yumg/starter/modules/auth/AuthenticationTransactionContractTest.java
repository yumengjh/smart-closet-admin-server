package com.yumg.starter.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.auth.application.AuthenticationService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class AuthenticationTransactionContractTest {
    @Test
    void refreshDoesNotRollbackSecurityStateWhenRejectingAReusedToken() throws Exception {
        Method refresh = AuthenticationService.class.getMethod("refresh", String.class);
        Transactional transaction = refresh.getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.noRollbackFor()).contains(ApiException.class);
        // The inner participant must share the rule, otherwise it marks the transaction rollback-only (#157).
        Method rotate = com.yumg.starter.modules.auth.application.TokenService.class
                .getMethod("rotate", com.yumg.starter.entities.User.class, String.class);
        assertThat(rotate.getAnnotation(Transactional.class).noRollbackFor()).contains(ApiException.class);
    }
}
