package com.yumg.starter.modules.rbac.application;

import com.yumg.starter.entities.User;
import com.yumg.starter.entities.Role;
import com.yumg.starter.entities.Permission;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.auth.application.TokenService;
import com.yumg.starter.modules.rbac.api.dto.RoleResponse;
import com.yumg.starter.modules.rbac.infrastructure.PermissionRepository;
import com.yumg.starter.modules.rbac.infrastructure.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yumg.starter.modules.security.application.AuditService;
import com.yumg.starter.modules.rbac.api.dto.RoleRequest;
import com.yumg.starter.modules.rbac.api.dto.PermissionRequest;

@Service
public class RbacService {
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final UserRepository users;
    private final TokenService tokens;
    private final AuditService audit;
    private final SuperAdminGuard superAdmins;
    /** Roles the platform itself depends on (bootstrap admin, default registration role); never deleted as a whole. */
    static final java.util.Set<String> SYSTEM_ROLES = java.util.Set.of(SuperAdminGuard.ROLE, "USER");
    public RbacService(RoleRepository roles, PermissionRepository permissions, UserRepository users, TokenService tokens, AuditService audit, SuperAdminGuard superAdmins) { this.roles = roles; this.permissions = permissions; this.users = users; this.tokens = tokens; this.audit = audit; this.superAdmins = superAdmins; }
    @Transactional public void grantDefaultUserRole(User user) { user.grant(roles.findByCode("USER").orElseThrow()); }
    @Transactional(readOnly = true) public java.util.List<RoleResponse> listRoles() { return roles.findAllWithPermissions().stream().map(RoleResponse::from).toList(); }
    @Transactional(readOnly = true) public java.util.List<com.yumg.starter.modules.rbac.api.dto.PermissionResponse> listPermissions() { return permissions.findAll().stream().map(com.yumg.starter.modules.rbac.api.dto.PermissionResponse::from).toList(); }
    @Transactional public RoleResponse createRole(RoleRequest request) { if(roles.findByCode(request.code()).isPresent()) throw ApiException.conflict(); Role role=roles.save(new Role(request.code(),request.displayName().trim())); audit.event("ROLE_CREATED","Role",role.getCode()); return RoleResponse.from(role); }
    @Transactional public RoleResponse updateRole(String code, RoleRequest request) { if(!code.equals(request.code())) throw ApiException.conflict(); Role role=role(code); role.rename(request.displayName().trim()); audit.event("ROLE_UPDATED","Role",code); return RoleResponse.from(role); }
    @Transactional public void deleteRole(String code) { Role role=role(code); if(SYSTEM_ROLES.contains(code)) throw ApiException.systemRoleProtected(); users.findDistinctByRoles_Code(code).forEach(user->{user.revoke(role);invalidate(user);}); roles.delete(role); audit.event("ROLE_DELETED","Role",code); }
    @Transactional public com.yumg.starter.modules.rbac.api.dto.PermissionResponse createPermission(PermissionRequest request) { if(permissions.findByCode(request.code()).isPresent()) throw ApiException.conflict(); Permission permission=permissions.save(new Permission(request.code(),request.description())); audit.event("PERMISSION_CREATED","Permission",permission.getCode()); return com.yumg.starter.modules.rbac.api.dto.PermissionResponse.from(permission); }
    @Transactional public com.yumg.starter.modules.rbac.api.dto.PermissionResponse updatePermission(String code, PermissionRequest request) { if(!code.equals(request.code())) throw ApiException.conflict(); Permission permission=permission(code); permission.changeDescription(request.description()); audit.event("PERMISSION_UPDATED","Permission",code); return com.yumg.starter.modules.rbac.api.dto.PermissionResponse.from(permission); }
    @Transactional public void deletePermission(String code) { Permission permission=permission(code); roles.findAllWithPermissions().stream().filter(role -> role.getPermissions().stream().anyMatch(item -> item.getCode().equals(code))).forEach(role->{role.revoke(permission);invalidateRoleMembers(role.getCode());}); permissions.delete(permission); audit.event("PERMISSION_DELETED","Permission",code); }
    @Transactional public void assignRole(String userId, String roleCode) { User user = user(userId); user.grant(role(roleCode)); invalidate(user); audit.event("USER_ROLE_ASSIGNED","User",userId,"SUCCESS","{\"role\":\""+roleCode+"\"}"); }
    @Transactional public void removeRole(String userId, String roleCode) { User user = user(userId); if (SuperAdminGuard.ROLE.equals(roleCode)) superAdmins.requireAnotherSuperAdmin(user); user.revoke(role(roleCode)); invalidate(user); audit.event("USER_ROLE_REMOVED","User",userId,"SUCCESS","{\"role\":\""+roleCode+"\"}"); }
    @Transactional public void grantPermission(String roleCode, String permissionCode) { Role role = role(roleCode); role.grant(permission(permissionCode)); invalidateRoleMembers(roleCode); audit.event("ROLE_PERMISSION_GRANTED","Role",roleCode,"SUCCESS","{\"permission\":\""+permissionCode+"\"}"); }
    @Transactional public void revokePermission(String roleCode, String permissionCode) { Role role = role(roleCode); role.revoke(permission(permissionCode)); invalidateRoleMembers(roleCode); audit.event("ROLE_PERMISSION_REVOKED","Role",roleCode,"SUCCESS","{\"permission\":\""+permissionCode+"\"}"); }
    private void invalidateRoleMembers(String roleCode) { users.findDistinctByRoles_Code(roleCode).forEach(this::invalidate); }
    private void invalidate(User user) { user.invalidateSessions(); tokens.revokeAllForUser(user.getId()); }
    private User user(String id) { return users.findById(id).orElseThrow(ApiException::notFound); }
    private Role role(String code) { return roles.findByCode(code).orElseThrow(ApiException::notFound); }
    private Permission permission(String code) { return permissions.findByCode(code).orElseThrow(ApiException::notFound); }
}
