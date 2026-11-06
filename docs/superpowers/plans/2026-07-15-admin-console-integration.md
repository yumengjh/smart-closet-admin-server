# 管理后台完整集成 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `api-test.html` 的正式后台能力迁移为按 RBAC 控制的 Element Plus 多页面管理后台。

**Architecture:** 后端只扩展受控组件键与系统初始化菜单；前端以按领域拆分的 API 模块和 Element Plus 页面消费既有 HTTP API。导航权限决定菜单是否下发，`useCurrentPermissions` 决定写按钮是否显示，后端 `@PreAuthorize` 仍负责最终授权。

**Tech Stack:** Spring Boot 4、JPA、Liquibase、Spring Security、Vue 3、Pinia、Vue Router、Element Plus、TypeScript。

## Global Constraints

- 使用 Element Plus，不嵌入或复用 `api-test.html` 的调试 UI。
- 不新增业务 API、外链菜单或 iframe；Swagger/OpenAPI 仅通过新窗口链接打开。
- `welcome` 继续是静态首页；动态菜单不得包含已注册的 `/welcome`。
- 写按钮检查 Pinia 用户权限；后端接口权限是唯一安全边界。
- 新行为先写失败测试或可执行 Node 验证脚本；不提交、不推送。

---

## File structure

- 后端：`NavigationService` 白名单、`NavigationBootstrap` 内置目录和页面种子、导航服务测试。
- 前端 API：`admin-users.ts`、`rbac.ts`、`runtime-config.ts`、`security.ts`、`announcements.ts`。
- 前端通用：`useCurrentPermissions.ts`、路由组件注册表和路由转换验证脚本。
- 前端页面：用户、RBAC、配置、IP 规则、审计、公告、账户安全和首页概览。

### Task 1: 扩展受控组件键与系统菜单种子

**Status: completed**

**Files:**
- Modify: `src/main/java/com/yumg/starter/modules/navigation/application/NavigationService.java`
- Modify: `src/main/java/com/yumg/starter/modules/navigation/application/NavigationBootstrap.java`
- Modify: `src/test/java/com/yumg/starter/modules/navigation/application/NavigationServiceTest.java`
- Modify: `../GitHub/springboot-admin/src/router/utils.ts`
- Modify: `../GitHub/springboot-admin/scripts/router-conversion.test.mjs`

**Interfaces:**
- Accept component keys: `user-management`, `rbac-management`, `runtime-config`, `ip-rule-management`, `audit-log`, `announcement-management`, `account-security`.
- Seed `system`、`content`、`account` directories and their child pages as `systemManaged=true`.
- Preserve static `/welcome`; `filterRegisteredRoutePaths` removes it from dynamic menu records.

- [ ] Write failing backend tests that create a `user-management` page successfully and reject `untrusted-page`.
- [ ] Run `./mvnw -Dtest=NavigationServiceTest test`; expect unsupported-key assertion to fail before whitelist update.
- [ ] Add the seven keys to `COMPONENT_KEYS`; seed pages with their documented paths and read permissions, creating parent directories before their children.
- [ ] Add matching front-end registry entries:

```ts
"user-management": () => import("@/views/system/users/index.vue"),
"rbac-management": () => import("@/views/system/rbac/index.vue"),
"runtime-config": () => import("@/views/system/config/index.vue"),
"ip-rule-management": () => import("@/views/system/ip-rules/index.vue"),
"audit-log": () => import("@/views/system/audit/index.vue"),
"announcement-management": () => import("@/views/content/announcements/index.vue"),
"account-security": () => import("@/views/account/security/index.vue")
```
- [ ] Extend `router-conversion.test.mjs` with every supported component key and assert each produces a route with no `children` property when leaf.
- [ ] Run `./mvnw -Dtest=NavigationServiceTest test` and `node scripts/router-conversion.test.mjs`; expect both pass.

### Task 2: API clients and permission composable

**Status: completed**

**Files:**
- Create: `../GitHub/springboot-admin/src/api/admin-users.ts`
- Create: `../GitHub/springboot-admin/src/api/rbac.ts`
- Create: `../GitHub/springboot-admin/src/api/runtime-config.ts`
- Create: `../GitHub/springboot-admin/src/api/security.ts`
- Create: `../GitHub/springboot-admin/src/api/announcements.ts`
- Create: `../GitHub/springboot-admin/src/composables/useCurrentPermissions.ts`
- Test: `../GitHub/springboot-admin/scripts/permission-composable.test.mjs`

**Interfaces:**
- Every API client consumes `ApiResponse<T>` from `src/api/user.ts` and calls `http.request` with relative `/api/v1` paths.
- `useCurrentPermissions()` produces `can(permission: string): boolean` from `useUserStoreHook().permissions`.
- API functions include `listUsers(params)`, `updateUserStatus(id,status)`, `assignUserRole(id,roleCode)`, `listRoles()`, `listPermissions()`, `listRuntimeSettings()`, `listIpRules()`, `listAuditEvents(page,size)`, and announcement CRUD/public preview functions.

- [ ] Write a Node test that passes `['system:user:read']` into the pure permission predicate and expects read true/write false.
- [ ] Run the test; expect missing module/function failure.
- [ ] Implement a small exported pure `hasPermission(permissions, permission)` helper and wrap it in the composable; avoid `router.meta.auths`.
- [ ] Implement typed DTOs from `docs/API_REFERENCE.md`, including `PageResponse<T>`, `RuntimeSetting`, `IpAccessRule`, `AuditEvent`, and `Announcement`.

```ts
export const hasPermission = (permissions: readonly string[], code: string) =>
  permissions.includes(code);
export function useCurrentPermissions() {
  const permissions = computed(() => useUserStoreHook().permissions);
  return { can: (code: string) => hasPermission(permissions.value, code) };
}
```
- [ ] Run `node scripts/permission-composable.test.mjs` and `pnpm typecheck`; expect pass.

### Task 3: 用户管理与 RBAC 页面

**Status: completed**

**Files:**
- Create: `../GitHub/springboot-admin/src/views/system/users/index.vue`
- Create: `../GitHub/springboot-admin/src/views/system/rbac/index.vue`
- Test: `../GitHub/springboot-admin/scripts/admin-page-contract.test.mjs`

**Interfaces:**
- User page calls `listUsers({q,status,page,size})`, `getAdminUser(id)`, `updateUserStatus`, `revokeUserSessions`, `assignUserRole`, `removeUserRole`.
- RBAC page calls role/permission CRUD plus `grantRolePermission` and `revokeRolePermission`.
- Buttons use `can('system:user:write')`, `can('system:role:assign')`, and `can('system:role:write')`.

- [ ] Write a contract test asserting user status payload is `{status:'DISABLED'}` and role update payload retains its immutable `code`.
- [ ] Run it; expect missing exported builders/functions.
- [ ] Implement user table with search/status filters, server pagination, selected-user detail drawer, role select, status select, and revoke confirmation.
- [ ] Implement RBAC tabs: roles table and permissions table; role permission checkbox list computes additions/removals and applies each request sequentially, stopping on first failure before reloading role data.
- [ ] Hide only unavailable write controls; retain readable tables for read-only users and surface server `409` messages through Element Plus notifications.

```ts
export const userStatusPayload = (status: UserStatus) => ({ status });
export const rolePayload = (code: string, displayName: string) => ({ code, displayName });
```
- [ ] Run the contract script and `pnpm typecheck`; expect pass.

### Task 4: 配置、安全与审计页面

**Status: completed**

**Files:**
- Create: `../GitHub/springboot-admin/src/views/system/config/index.vue`
- Create: `../GitHub/springboot-admin/src/views/system/ip-rules/index.vue`
- Create: `../GitHub/springboot-admin/src/views/system/audit/index.vue`
- Test: `../GitHub/springboot-admin/scripts/runtime-config-contract.test.mjs`

**Interfaces:**
- Runtime page reads all settings once and groups keys into `security`、`rateLimit`、`cors`、`endpointPolicy`、`ipPolicy`、`identity`.
- IP rule page converts `Date | null` to epoch milliseconds or `null` before `createIpRule`.
- Audit page calls `listAuditEvents(page,size)` and renders `PageResponse<AuditEvent>`.

- [ ] Write failing contract tests for `toEpochOrNull(undefined) === null` and `toEpochOrNull(new Date('2026-01-01T00:00:00Z'))` matching `getTime()`.
- [ ] Run the script; expect missing conversion helper.
- [ ] Implement the pure conversion helper and API grouping constants.
- [ ] Build runtime configuration groups with `el-switch`, `el-input-number`, and `el-input type="textarea"`; before saving CORS/IP entries show `ElMessageBox.confirm` warning.
- [ ] Build persistent IP rule table/dialog with ALLOW/DENY tag, network, expiry, reason and delete confirmation.
- [ ] Build audit read-only paginated table with result tags and expandable formatted metadata.

```ts
export const toEpochOrNull = (value?: Date | null) => value ? value.getTime() : null;
export const settingGroups = {
  security: ["security.brute-force.enabled", "security.audit.enabled", "security.request-log.enabled"],
  rateLimit: ["security.rate-limit.enabled", "security.rate-limit.capacity", "security.rate-limit.window-seconds"],
  cors: ["security.cors.allowed-origins", "security.cors.allowed-methods"],
  endpointPolicy: ["security.endpoint.disabled-patterns", "security.endpoint.rate-limit.patterns", "security.endpoint.rate-limit.capacity", "security.endpoint.rate-limit.window-seconds"],
  ipPolicy: ["security.ip.allow-list", "security.ip.deny-list"],
  identity: ["identity.registration.enabled"]
};
```
- [ ] Run contract script and `pnpm typecheck`; expect pass.

### Task 5: 公告、个人安全与系统概览

**Status: completed**

**Files:**
- Create: `../GitHub/springboot-admin/src/views/content/announcements/index.vue`
- Create: `../GitHub/springboot-admin/src/views/account/security/index.vue`
- Modify: `../GitHub/springboot-admin/src/views/welcome/index.vue`
- Test: `../GitHub/springboot-admin/scripts/announcement-contract.test.mjs`

**Interfaces:**
- Announcement form payload is `{title, content}`; publication calls `setAnnouncementPublication(id, published)`.
- Account page calls profile, profile update, password update, session list and revoke-all; revoke-all calls `useUserStoreHook().clearSession()` then `router.replace('/login')` only after success.
- Welcome displays `health.status`, system information and `window.open` links for `/swagger-ui/index.html` and `/v3/api-docs`.

- [ ] Write a failing announcement payload test that rejects an empty title/content and accepts a non-empty `{title, content}` object.
- [ ] Run the script; expect missing helper.
- [ ] Implement the pure payload validator, then announcement table/editor with create, update, publish/withdraw, delete, and public preview in a new browser tab.
- [ ] Implement profile and password forms, session list, and all-device-revoke confirmation; on success clear client session and redirect to login.
- [ ] Extend welcome page with read-only health/system cards and safe external-window document buttons.

```ts
export const announcementPayload = (title: string, content: string) => {
  if (!title.trim() || !content.trim()) throw new Error("title and content are required");
  return { title: title.trim(), content: content.trim() };
};
```
- [ ] Run the announcement script and `pnpm typecheck`; expect pass.

### Task 6: 文档、integration verification and handoff

**Status: completed — implementation checks and production build passed.**

**Files:**
- Modify: `docs/API_REFERENCE.md`
- Modify: `docs/api/rbac.md`
- Modify: `task_plan.md`
- Modify: `progress.md`

**Interfaces:**
- Document new menu component keys and seed routes; no backend API contract changes.

- [ ] Update documentation with the page-to-permission map and the fact that UI controls are convenience checks, not authorization.
- [ ] Start or reuse the local backend and confirm `GET /api/v1/navigation/routes` returns each expected page for a freshly refreshed super-admin token.
- [ ] Verify one read-only account does not see write buttons and a direct write request is denied by the server.
- [x] Run `./mvnw test` in backend; run `node scripts/router-conversion.test.mjs`, all new Node scripts, `pnpm typecheck`, `pnpm build`, and `git diff --check` in frontend.
- [x] Add the missing direct frontend dependencies and rerun the production build successfully.
- [x] Mark phase 10 complete in `task_plan.md` and update `progress.md` with command results.
