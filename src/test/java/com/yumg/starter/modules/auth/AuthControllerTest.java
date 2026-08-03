package com.yumg.starter.modules.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yumg.starter.modules.auth.api.AuthController;
import com.yumg.starter.modules.auth.api.dto.UserResponse;
import com.yumg.starter.modules.auth.application.RegistrationUseCase;
import com.yumg.starter.modules.auth.application.AuthenticationUseCase;
import com.yumg.starter.common.api.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    private MockMvc mvc;
    private RegistrationUseCase registrationService;
    private AuthenticationUseCase authenticationService;

    @BeforeEach
    void setUp() {
        registrationService = request -> new UserResponse("user-1", "alice", "Alice", "ACTIVE",
                "/api/v1/avatars/public-avatar?v=0");
        authenticationService = new AuthenticationUseCase() {
            @Override
            public com.yumg.starter.modules.auth.api.dto.TokenResponse login(
                    com.yumg.starter.modules.auth.api.dto.LoginRequest request) {
                return new com.yumg.starter.modules.auth.api.dto.TokenResponse(
                        "access", "refresh", "Bearer", 900, true);
            }

            @Override
            public com.yumg.starter.modules.auth.api.dto.TokenResponse refresh(String refreshToken) {
                return login(null);
            }

            @Override
            public void logout(String refreshToken) {
            }
        };
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(registrationService, authenticationService, false))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void registersANewUserAndNeverReturnsPasswordData() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"username":"Alice","displayName":"Alice","password":"correct-horse-battery"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("user-1"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.avatarUrl").value("/api/v1/avatars/public-avatar?v=0"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void rejectsAnInvalidRegistrationRequest() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("""
                                {"username":"x","displayName":"","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void writesPersistentHttpOnlyRefreshCookieAndNeverReturnsItInLoginBody() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"correct-horse-battery\",\"rememberMe\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("refresh_token=refresh"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"),
                        org.hamcrest.Matchers.containsString("Max-Age=2592000"))));
    }
}
