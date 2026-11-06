# Login Session Family Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Present one understandable login session per refresh-token family while preserving token rotation security.

**Architecture:** `RefreshSession` remains an immutable rotation-chain record. `UserProfileService` groups records by `familyId`, selects the newest record in each family, and maps it to a session-family response. The Vue account-security page consumes that response and removes its manual refresh-token action.

**Tech Stack:** Spring Boot 4, Spring Data JPA, JUnit 5/Mockito, Vue 3, TypeScript, Element Plus.

## Global Constraints

- Keep Refresh Token rotation, family revocation, and reuse detection unchanged.
- Do not return refresh-token values, token hashes, or raw database IDs to the page.
- Require no new database migration.
- Do not create commits; the working tree contains user-owned changes.

---

### Task 1: Aggregate refresh-token rows into login sessions

**Files:**
- Modify: `src/main/java/com/yumg/starter/entities/RefreshSession.java`
- Modify: `src/main/java/com/yumg/starter/modules/users/api/dto/SessionResponse.java`
- Modify: `src/main/java/com/yumg/starter/modules/users/application/UserProfileService.java`
- Test: `src/test/java/com/yumg/starter/modules/users/application/UserProfileServiceTest.java`

**Interfaces:**
- Consumes: `List<RefreshSession> findAllByUserIdOrderByIssuedAtDesc(String userId)`.
- Produces: `List<SessionResponse> sessions(String userId)` where each response represents one `familyId`.

- [ ] **Step 1: Write the failing test**

```java
when(sessions.findAllByUserIdOrderByIssuedAtDesc("user-id"))
    .thenReturn(List.of(rotated, current, anotherDevice));

assertThat(service.sessions("user-id"))
    .extracting(SessionResponse::status)
    .containsExactly("ACTIVE", "ACTIVE");
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -Dtest=UserProfileServiceTest test`

Expected: FAIL because the current endpoint returns every rotation record and has no session-family status.

- [ ] **Step 3: Write minimal implementation**

```java
return sessions.findAllByUserIdOrderByIssuedAtDesc(userId).stream()
    .collect(Collectors.groupingBy(RefreshSession::getFamilyId))
    .values().stream()
    .map(rows -> rows.stream().max(comparing(RefreshSession::getIssuedAt)).orElseThrow())
    .sorted(comparing(RefreshSession::getIssuedAt).reversed())
    .map(SessionResponse::from)
    .toList();
```

Expose `firstIssuedAt`, `lastActiveAt`, `expiresAt`, and status derived from revoked, consumed, and expiration fields; do not expose IDs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -Dtest=UserProfileServiceTest test`

Expected: PASS.

### Task 2: Replace token-record UI with session-family UI

**Files:**
- Modify: `../GitHub/springboot-admin/src/api/user.ts`
- Modify: `../GitHub/springboot-admin/src/views/account/security/index.vue`
- Modify: `../GitHub/springboot-admin/scripts/avatar-session-menu.test.mjs`

**Interfaces:**
- Consumes: `Session { firstIssuedAt, lastActiveAt, expiresAt, status }`.
- Produces: a session table with no raw session ID and no manual refresh button.

- [ ] **Step 1: Write the failing UI contract test**

```js
assert.doesNotMatch(source, /refreshSession/);
assert.doesNotMatch(source, /会话 ID/);
assert.match(source, /最近活动/);
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node scripts/avatar-session-menu.test.mjs`

Expected: FAIL because the account-security page exposes `refreshSession` and a raw session-ID column.

- [ ] **Step 3: Write minimal implementation**

Remove `handRefreshToken` usage from this page and replace the table with status, first login time, last activity, and expiration columns. Keep current logout and revoke-all actions.

- [ ] **Step 4: Run typecheck and UI contract test**

Run: `node scripts/avatar-session-menu.test.mjs && pnpm typecheck`

Expected: both commands exit 0.

### Task 3: Verify the integrated behavior

**Files:**
- Modify: no production files

- [ ] **Step 1: Run backend test suite**

Run: `./mvnw test`

Expected: exit 0 with no failures.

- [ ] **Step 2: Run frontend build**

Run: `pnpm build`

Expected: exit 0.

- [ ] **Step 3: Restart backend and inspect API schema**

Run: `curl -fsS http://localhost:8080/actuator/health && curl -fsS http://localhost:8080/v3/api-docs | rg -q '"/api/v1/users/me/sessions"'`

Expected: health status `UP` and the sessions endpoint remains documented.
