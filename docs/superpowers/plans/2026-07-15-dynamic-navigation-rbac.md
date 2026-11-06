# 动态导航与 RBAC 集成 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供由数据库菜单驱动、按现有 RBAC 权限过滤的动态路由及其管理界面。

**Architecture:** 后端 `navigation` 模块保存受控组件键和菜单树，读取接口按 JWT 权限过滤，管理接口受 `system:menu:*` 保护。前端以显式组件注册表将组件键转换为路由组件，初始化失败时保留静态首页而不让侧栏永久加载。

**Tech Stack:** Spring Boot 4、JPA、Liquibase、Spring Security、Vue 3、Pinia、Vue Router、Element Plus、TypeScript。

## Global Constraints

- 仅支持内部页面；禁止外链、iframe 和后端下发任意文件路径。
- 组件键仅允许 `welcome`、`permission-page`、`menu-management`。
- 复用 `system:menu:read` 与 `system:menu:write` RBAC 权限；业务 API 的 `@PreAuthorize` 仍是最终授权边界。
- 内置首页和菜单管理不可删除或禁用。
- 新行为先写失败测试；不提交、不推送。

---

## File structure

- `entities/NavigationMenu.java`：菜单持久化模型和领域状态转换。
- `modules/navigation/**`：菜单读取、校验、CRUD、DTO、控制器与 Repository。
- `db/changelog/changes/008-navigation.yaml`：菜单表与唯一/排序索引。
- `modules/rbac/application/RbacBootstrap.java`：初始化菜单权限。
- `src/router/utils.ts`、`src/api/routes.ts`：受控组件注册、动态路由加载和无阻塞降级。
- `src/views/system/menu/index.vue`：菜单管理 Element Plus 页面。

### Task 1: Navigation persistence and bootstrap

**Files:**
- Create: `src/main/java/com/yumg/starter/entities/NavigationMenu.java`
- Create: `src/main/java/com/yumg/starter/entities/NavigationMenuType.java`
- Create: `src/main/java/com/yumg/starter/modules/navigation/infrastructure/NavigationMenuRepository.java`
- Create: `src/main/resources/db/changelog/changes/008-navigation.yaml`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `src/main/java/com/yumg/starter/modules/rbac/application/RbacBootstrap.java`
- Test: `src/test/java/com/yumg/starter/persistence/SqliteMigrationTest.java`

**Interfaces:**
- Produces `NavigationMenu(code, title, routePath, componentKey, type, ...)` and `findAllByOrderBySortOrderAscCodeAsc()`.
- Produces `system:menu:read` and `system:menu:write` for `SUPER_ADMIN`.

- [x] Write a migration assertion that `navigation_menus` exists with unique `code` and `route_path` indexes; run `./mvnw -Dtest=SqliteMigrationTest test` and observe failure.
- [x] Add the enum/entity/repository, changelog table/indexes and bootstrap permissions; run the same command and observe pass.

### Task 2: Menu service, API, and authorization

**Files:**
- Create: `src/main/java/com/yumg/starter/modules/navigation/application/NavigationService.java`
- Create: `src/main/java/com/yumg/starter/modules/navigation/application/NavigationBootstrap.java`
- Create: `src/main/java/com/yumg/starter/modules/navigation/api/NavigationController.java`
- Create: `src/main/java/com/yumg/starter/modules/navigation/api/dto/NavigationMenuRequest.java`
- Create: `src/main/java/com/yumg/starter/modules/navigation/api/dto/NavigationMenuResponse.java`
- Create: `src/main/java/com/yumg/starter/modules/navigation/api/dto/NavigationRouteResponse.java`
- Test: `src/test/java/com/yumg/starter/modules/navigation/application/NavigationServiceTest.java`

**Interfaces:**
- `List<NavigationRouteResponse> routes(Set<String> permissions)` filters enabled/visible trees.
- `NavigationMenuResponse create(NavigationMenuRequest request)`, `update(String id, ...)`, `delete(String id)` validate component, parent, permissions and built-ins.
- `GET /api/v1/navigation/routes`, `/menus`; `POST|PUT|DELETE /menus`.

- [x] Write failing tests for permission filtering, invalid component key, invalid parent, protected builtin delete, and CRUD.
- [x] Implement minimal validation and tree builder; add controller `@PreAuthorize` guards and audit events.
- [x] Run `./mvnw -Dtest=NavigationServiceTest test`, then full `./mvnw test`.

### Task 3: Safe dynamic route loading

**Files:**
- Modify: `../GitHub/springboot-admin/src/api/routes.ts`
- Modify: `../GitHub/springboot-admin/src/router/utils.ts`
- Modify: `../GitHub/springboot-admin/src/store/modules/user.ts`
- Modify: `../GitHub/springboot-admin/src/router/index.ts`

**Interfaces:**
- `getAsyncRoutes(): Promise<ApiResponse<NavigationRoute[]>>` reads `/navigation/routes`.
- `routeComponentRegistry: Record<ComponentKey, RouteComponent>` rejects unknown keys.
- `initRouter(): Promise<Router>` always settles and installs routes once per session.

- [x] Add a guarded conversion that produces no route for an unknown `componentKey` rather than an `undefined` component.
- [x] Replace path searching with the registry, load routes after profile load, and make route initialization catch errors while retaining `/welcome`.
- [ ] Run `pnpm typecheck` and `pnpm build` (typecheck passed; build is blocked by the existing missing `vue-demi` package metadata).

### Task 4: Menu administration page

**Files:**
- Create: `../GitHub/springboot-admin/src/views/system/menu/index.vue`
- Create: `../GitHub/springboot-admin/src/api/navigation.ts`
- Modify: `../GitHub/springboot-admin/src/router/utils.ts` component registry

**Interfaces:**
- `listMenus`, `createMenu`, `updateMenu`, `deleteMenu` consume the menu CRUD endpoints.
- The page only displays when its seeded `system:menu:read` route is returned; write buttons use `system:menu:write`.

- [x] Add the Element Plus tree table, dialog form, controlled component-key select, permission input/select and delete confirmation.
- [x] On successful mutation reload the management tree and dynamically installed menu routes.
- [ ] Run `pnpm typecheck && pnpm build` (typecheck passed; build is blocked by the existing missing `vue-demi` package metadata).

### Task 5: Documentation and integration verification

**Files:**
- Modify: `docs/api/rbac.md`
- Modify: `docs/API_REFERENCE.md`
- Test: backend integration with temporary SQLite service and frontend production build.

- [x] Document schemas, endpoints, required permissions and restricted component keys.
- [x] Start a local instance and verify the protected navigation endpoint plus seeded built-in records; authorization and protected-delete behaviors are covered by service tests.
- [x] Run `./mvnw test`, `pnpm typecheck`, and `git diff --check` in both repositories. `pnpm build` remains blocked by the existing dependency installation issue.
