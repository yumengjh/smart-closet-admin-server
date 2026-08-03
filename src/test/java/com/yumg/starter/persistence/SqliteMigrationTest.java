package com.yumg.starter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.yumg.starter.modules.maintenance.application.GcExecutionService;
import com.yumg.starter.modules.maintenance.application.GcPolicyService;
import com.yumg.starter.modules.maintenance.application.GcResource;
import com.yumg.starter.modules.maintenance.application.GcResourceDescriptor;
import com.yumg.starter.modules.users.application.UserAdministrationService;
import com.yumg.starter.entities.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@Import(SqliteMigrationTest.FailingGcResourceConfiguration.class)
class SqliteMigrationTest {

    private static final Set<String> CORE_TABLES = Set.of("users", "roles", "permissions",
            "user_roles", "role_permissions", "refresh_sessions", "system_settings",
            "ip_access_rules", "audit_events", "announcements", "navigation_menus",
            "maintenance_gc_policies",
            "maintenance_gc_runs", "maintenance_gc_locks", "storage_objects",
            "avatar_candidates", "runtime_component_statuses", "runtime_cache_revisions");

    @TempDir
    static Path temporaryDirectory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GcExecutionService gc;

    @Autowired
    private GcPolicyService gcPolicies;

    @Autowired
    private UserAdministrationService userAdministration;

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + temporaryDirectory.resolve("migration.db"));
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    }

    @Test
    void liquibaseCreatesCoreTables() {
        List<String> tables = jdbc.queryForList(
                "select name from sqlite_master where type = 'table'", String.class);

        assertThat(tables).containsAll(CORE_TABLES);
        assertThat(jdbc.queryForObject("select count(*) from databasechangelog", Integer.class))
                .isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForList(
                "select setting_key from system_settings where setting_key like 'runtime.%health%'",
                String.class))
                .contains("runtime.health-check.enabled", "runtime.health-check.interval-seconds")
                .doesNotContain("runtime.dependency-health.enabled",
                        "runtime.dependency-health.interval-seconds");

        assertThat(indexColumns("users", true)).contains(List.of("username"));
        assertThat(indexColumns("refresh_sessions", true)).contains(List.of("token_hash"));
        assertThat(indexColumns("user_roles", true)).contains(List.of("user_id", "role_id"));
        assertThat(indexColumns("role_permissions", true))
                .contains(List.of("role_id", "permission_id"));
        assertThat(indexColumns("navigation_menus", true))
                .contains(List.of("code"), List.of("route_path"));
        assertThat(indexNames("users")).contains("idx_users_username");
        assertThat(indexNames("permissions")).contains("idx_permissions_code");
        assertThat(indexNames("refresh_sessions")).contains("idx_refresh_sessions_token_hash");
        assertThat(indexNames("audit_events")).contains("idx_audit_events_occurred_at");
        assertThat(indexNames("ip_access_rules")).contains("idx_ip_access_rules_lookup");
        assertThat(indexNames("announcements")).contains("idx_announcements_publication");
        assertThat(indexNames("navigation_menus")).contains("idx_navigation_menus_parent_sort");
        assertThat(indexNames("refresh_sessions")).contains("idx_refresh_sessions_user_issued",
                "idx_refresh_sessions_family", "idx_refresh_sessions_expires_at");
        assertThat(indexNames("maintenance_gc_runs")).contains("idx_maintenance_gc_runs_started_at");
        assertThat(indexNames("users")).contains("uk_users_avatar_public_id");
        assertThat(indexNames("storage_objects")).contains(
                "uk_storage_objects_object_key", "idx_storage_objects_namespace_state");
        assertThat(indexNames("avatar_candidates")).contains(
                "uk_avatar_candidates_public_id", "idx_avatar_candidates_user_expiry");
        assertThat(indexNames("runtime_component_statuses"))
                .contains("uk_runtime_component_statuses_code");
        assertThat(indexNames("runtime_cache_revisions"))
                .contains("uk_runtime_cache_revisions_code");

        assertThat(foreignKeyTargets("refresh_sessions")).contains("users");
        assertThat(foreignKeyTargets("announcements")).contains("users");
        assertThat(foreignKeyTargets("role_permissions")).contains("roles", "permissions");
        assertThat(foreignKeyTargets("avatar_candidates")).contains("users", "storage_objects");
        assertThat(tables).doesNotContain("navigation_menu_roles", "resume_documents", "resume_feedbacks");
        assertThat(columnType("users", "id")).isEqualToIgnoringCase("varchar(36)");
        assertThat(columnType("users", "status")).isEqualToIgnoringCase("varchar(32)");
        assertThat(columnType("users", "avatar_public_id")).isEqualToIgnoringCase("varchar(36)");
        assertThat(columnType("users", "avatar_type")).isEqualToIgnoringCase("varchar(32)");
        assertThat(columnType("users", "avatar_seed")).isEqualToIgnoringCase("varchar(64)");
        assertThat(columnType("users", "avatar_revision")).isEqualToIgnoringCase("bigint");
        assertThat(columnType("audit_events", "version")).isEqualToIgnoringCase("bigint");
    }

    @Test
    void rejectsRefreshSessionForMissingUser() {
        assertThat(jdbc.queryForObject("pragma foreign_keys", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("""
                insert into refresh_sessions
                    (id, user_id, family_id, token_hash, issued_at, expires_at, version)
                values (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000099",
                "00000000-0000-0000-0000-000000000002",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void combinesUserQueryAndStatusAndLoadsRoles() {
        String activeId = "00000000-0000-0000-0000-000000000701";
        String disabledId = "00000000-0000-0000-0000-000000000702";
        String timestamp = "2026-07-28T00:00:00Z";
        jdbc.update("""
                insert into users
                    (id, username, display_name, password_hash, status, token_version,
                     version, created_at, updated_at)
                values (?, ?, ?, 'hash', ?, 0, 0, ?, ?)
                """, activeId, "p1-filter-active", "P1 Filter Active", "ACTIVE",
                timestamp, timestamp);
        jdbc.update("""
                insert into users
                    (id, username, display_name, password_hash, status, token_version,
                     version, created_at, updated_at)
                values (?, ?, ?, 'hash', ?, 0, 0, ?, ?)
                """, disabledId, "p1-filter-disabled", "P1 Filter Disabled", "DISABLED",
                timestamp, timestamp);
        String userRoleId = jdbc.queryForObject(
                "select id from roles where code = 'USER'", String.class);
        jdbc.update("insert into user_roles (user_id, role_id) values (?, ?)",
                activeId, userRoleId);

        var result = userAdministration.list("p1-filter", UserStatus.ACTIVE, 0, 20);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(user -> {
            assertThat(user.id()).isEqualTo(activeId);
            assertThat(user.roles()).containsExactly("USER");
        });
    }

    @Test
    void gcActuallyDeletesExpiredAuditRowsFromTheDatabase() {
        String oldId = "00000000-0000-0000-0000-000000000777";
        jdbc.update("""
                insert into audit_events (id, action, result, occurred_at, version)
                values (?, ?, ?, ?, ?)
                """, oldId, "TEST_OLD_AUDIT", "SUCCESS", "2000-01-01T00:00:00Z", 0);
        gcPolicies.update("audit-events", true, false, 1, 100);

        var run = gc.run("MANUAL", false, List.of("audit-events"), "test-user");

        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select count(*) from audit_events where id = ?", Integer.class, oldId))
                .isZero();
    }

    @Test
    void gcRollsBackResourceWritesButRetainsTheFailedRunAndAudit() {
        assertThatThrownBy(() -> gc.run(
                "MANUAL", false, List.of("failing-test-resource"), "test-user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated GC failure");

        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where action = 'TEST_GC_ROLLBACK'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select status from maintenance_gc_runs where resource_codes = ?",
                String.class, "failing-test-resource")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where action = 'GC_RUN_FAILED'",
                Integer.class)).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingGcResourceConfiguration {
        @Bean
        GcResource failingGcResource(JdbcTemplate jdbc) {
            return new GcResource() {
                @Override
                public GcResourceDescriptor descriptor() {
                    return new GcResourceDescriptor(
                            "failing-test-resource", "失败事务测试", "仅用于事务回归测试",
                            1, 10, false);
                }

                @Override
                public com.yumg.starter.modules.maintenance.application.GcResourceResult execute(
                        com.yumg.starter.entities.MaintenanceGcPolicy policy,
                        boolean dryRun, java.time.Instant now) {
                    jdbc.update("""
                            insert into audit_events
                                (id, action, result, occurred_at, version)
                            values (?, 'TEST_GC_ROLLBACK', 'SUCCESS', ?, 0)
                            """, java.util.UUID.randomUUID().toString(), now.toString());
                    throw new IllegalStateException("simulated GC failure");
                }
            };
        }
    }

    private List<String> indexNames(String table) {
        return jdbc.queryForList("select name from pragma_index_list(?)", String.class, table);
    }

    private List<List<String>> indexColumns(String table, boolean unique) {
        List<String> indexes = jdbc.queryForList(
                "select name from pragma_index_list(?) where \"unique\" = ?", String.class,
                table, unique ? 1 : 0);
        return indexes.stream().map(index -> jdbc.queryForList(
                "select name from pragma_index_info(?) order by seqno", String.class, index)).toList();
    }

    private List<String> foreignKeyTargets(String table) {
        return jdbc.queryForList("select \"table\" from pragma_foreign_key_list(?)", String.class,
                table);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("select type from pragma_table_info(?) where name = ?",
                String.class, table, column);
    }
}
