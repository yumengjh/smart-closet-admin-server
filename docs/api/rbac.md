# RBAC 权限说明

权限模型由数据库维护：

```text
users --< user_roles >-- roles --< role_permissions >-- permissions
```

角色和权限都不是 Java 枚举，后续可在数据库中扩展。权限编码使用 `资源:动作`，例如 `system:role:assign`。

## 内置角色与权限

初始角色：`SUPER_ADMIN`、`ADMIN`、`USER`。`SUPER_ADMIN` 会获得当前所有内置权限；新注册用户会自动获得 `USER`。

内置权限包括：

- `system:user:read`、`system:user:write`
- `system:role:read`、`system:role:write`、`system:role:assign`
- `system:menu:read`、`system:menu:write`
- `system:config:read`、`system:config:write`
- `system:audit:read`
- `example:announcement:read`、`example:announcement:write`、`example:announcement:delete`

权限会写入新签发的 JWT；修改角色或权限后，用户应重新登录或刷新 Token。

角色或权限发生变更时，受影响用户的 Refresh Session 会被撤销且 token version 递增，旧 JWT 会立即失效。系统拒绝禁用、锁定或移除最后一个启用的 `SUPER_ADMIN`，并返回 `409 LAST_SUPER_ADMIN_PROTECTED`；该检查会对全部超级管理员行加写锁，避免并发降级各自通过。`SUPER_ADMIN` 与 `USER` 是系统角色，不论成员数都不能整体删除（`409 SYSTEM_ROLE_PROTECTED`）。

## 已实现接口

以下接口均位于 `/api/v1/rbac`，且需要对应的 JWT 权限：

- `GET /roles`：查看角色及其权限，需 `system:role:read`。
- `GET /permissions`：查看可分配的权限目录，需 `system:role:read`。
- `POST /roles`：创建角色，需 `system:role:write`。请求体：`{"code":"CONTENT_EDITOR","displayName":"内容编辑"}`。
- `PUT /roles/{roleCode}`：修改角色显示名，需 `system:role:write`。路径和请求体的 `code` 必须一致，角色编码不可改名。
- `DELETE /roles/{roleCode}`：删除角色，需 `system:role:write`；会从所有用户移除该角色并使受影响令牌失效。最后一个 `SUPER_ADMIN` 不可删除。
- `POST /permissions`：创建权限，需 `system:role:write`。请求体：`{"code":"content:article:publish","description":"发布文章"}`。
- `PUT /permissions/{permissionCode}`：修改权限说明，需 `system:role:write`。权限编码不可改名。
- `DELETE /permissions/{permissionCode}`：删除权限，需 `system:role:write`；会从所有角色撤销该权限并使受影响令牌失效。
- `PUT /users/{userId}/roles/{roleCode}`：给用户分配角色，需 `system:role:assign`。
- `DELETE /users/{userId}/roles/{roleCode}`：移除用户角色，需 `system:role:assign`。
- `PUT /roles/{roleCode}/permissions/{permissionCode}`：给角色授予权限，需 `system:role:write`。
- `DELETE /roles/{roleCode}/permissions/{permissionCode}`：移除角色权限，需 `system:role:write`。

`GET /roles` 的角色列表位于统一响应的 `data` 中；上述成功但无实体的 PUT/DELETE 操作返回 `204 No Content`。

角色编码必须匹配 `[A-Z_]{2,64}`；权限编码必须匹配 `resource:action` 的三段小写形式，例如 `content:article:publish`。目录修改、角色分配和权限分配均会记录审计事件。

## 管理员用户管理

- `GET /api/v1/admin/users`：列出用户，需 `system:user:read`。
- `PATCH /api/v1/admin/users/{id}/status`：更新状态，需 `system:user:write`。请求体示例：`{"status":"DISABLED"}`。
- `GET /api/v1/admin/users/{id}`：查看单用户详情，需 `system:user:read`。
- `POST /api/v1/admin/users/{id}/sessions/revoke`：撤销该用户全部 Refresh Session 并使现有 Access Token 立即失效，需 `system:user:write`，返回 `204`。

支持 `ACTIVE`、`LOCKED`、`DISABLED`。锁定默认持续 15 分钟；禁用或锁定会撤销该用户全部 Refresh Token，并递增 Token 版本。

## 动态菜单与路由

菜单由 `navigation_menus` 表维护，读取路由时后端会按 JWT 中的权限过滤不可见、已停用或无权限的菜单。组件不是由后端下发文件路径，而是受控组件键；当前允许 `welcome`、`permission-page`、`menu-management`、`user-management`、`rbac-management`、`runtime-config`、`ip-rule-management`、`audit-log`、`announcement-management`、`account-security`。外链和 iframe 不受支持。

- `GET /api/v1/navigation/routes`：所有已认证用户可调用，返回当前用户可访问的动态菜单树；无需额外菜单管理权限。
- `GET /api/v1/navigation/menus`：读取完整菜单树，需 `system:menu:read`。
- `POST /api/v1/navigation/menus`：创建菜单，需 `system:menu:write`。
- `PUT /api/v1/navigation/menus/{id}`：更新菜单，需 `system:menu:write`。菜单编码不可改名。
- `DELETE /api/v1/navigation/menus/{id}`：删除菜单，需 `system:menu:write`。有子菜单或内置菜单时返回 `409 CONFLICT`。

菜单写入请求示例：

```json
{
  "parentId": null,
  "code": "menu-manager",
  "title": "菜单管理",
  "routePath": "/system/menu",
  "componentKey": "menu-management",
  "icon": "Menu",
  "sortOrder": 100,
  "menuType": "PAGE",
  "requiredPermission": "system:menu:read",
  "visible": true,
  "enabled": true,
  "keepAlive": false
}
```

`menuType` 可为 `PAGE` 或 `DIRECTORY`；目录不得设置 `componentKey`，页面必须使用允许的组件键。引用的 `requiredPermission` 必须已存在；父级必须是目录，且不允许形成循环。系统会初始化首页、系统管理、内容管理、个人中心及其内置管理页面，它们不可删除、停用或修改。

`springboot-admin` 使用 Element Plus 实现这些页面。页面里的写按钮仅是当前 JWT 权限的交互提示；即使绕过前端直接请求，服务端仍会按 `@PreAuthorize` 拒绝没有写权限的调用。
