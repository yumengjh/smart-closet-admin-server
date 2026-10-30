package com.yumg.starter.modules.users.application;

import com.yumg.starter.modules.rbac.application.SuperAdminGuard;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.User;
import com.yumg.starter.entities.UserStatus;
import com.yumg.starter.modules.auth.application.TokenService;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.users.api.dto.AdminUserResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import com.yumg.starter.common.api.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yumg.starter.modules.security.application.AuditService;

@Service
public class UserAdministrationService {
    private final UserRepository users; private final TokenService tokens; private final AuditService audit;
    private final SuperAdminGuard superAdmins;
    private final com.yumg.starter.modules.security.application.BruteForceService bruteForce;
    public UserAdministrationService(UserRepository users, TokenService tokens, AuditService audit, SuperAdminGuard superAdmins,
                                     com.yumg.starter.modules.security.application.BruteForceService bruteForce) { this.users = users; this.tokens = tokens; this.audit = audit; this.superAdmins = superAdmins; this.bruteForce = bruteForce; }
    @Transactional(readOnly = true) public List<AdminUserResponse> list() { return users.findAllWithRoles().stream().map(AdminUserResponse::from).toList(); }
    @Transactional(readOnly = true) public PageResponse<AdminUserResponse> list(String query, UserStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size);
        String normalizedQuery = query == null || query.isBlank() ? null : query.strip();
        var source = users.search(normalizedQuery, status, pageable);
        if (source.isEmpty()) {
            return PageResponse.from(source.map(AdminUserResponse::from));
        }
        var ids = source.getContent().stream().map(User::getId).toList();
        var loadedById = new HashMap<String, User>();
        users.findAllWithRolesByIdIn(ids).forEach(user -> loadedById.put(user.getId(), user));
        var items = ids.stream().map(loadedById::get).map(AdminUserResponse::from).toList();
        return new PageResponse<>(items, source.getNumber(), source.getSize(),
                source.getTotalElements(), source.getTotalPages());
    }
    @Transactional(readOnly = true) public AdminUserResponse get(String id) { return AdminUserResponse.from(users.findWithRolesById(id).orElseThrow(ApiException::notFound)); }
    @Transactional public void revokeSessions(String id) { users.findById(id).orElseThrow(ApiException::notFound).invalidateSessions(); tokens.revokeAllForUser(id); audit.event("USER_SESSIONS_REVOKED","User",id); }
    @Transactional public AdminUserResponse changeStatus(String id, UserStatus status) {
        User user = users.findWithRolesById(id).orElseThrow(ApiException::notFound);
        if (status != UserStatus.ACTIVE) superAdmins.requireAnotherSuperAdmin(user);
        switch (status) { case ACTIVE -> { user.enable(); user.unlock(); bruteForce.clear(user.getUsername()); } case DISABLED -> user.disable(); case LOCKED -> user.lock(Instant.now().plus(15, ChronoUnit.MINUTES)); }
        tokens.revokeAllForUser(id);
        audit.event("USER_STATUS_UPDATED","User",id,"SUCCESS","{\"status\":\""+status+"\"}"); return AdminUserResponse.from(user);
    }
}
