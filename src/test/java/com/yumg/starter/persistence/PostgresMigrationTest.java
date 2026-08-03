package com.yumg.starter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("postgres")
@SpringBootTest(properties = "app.jwt.allow-ephemeral-key=true")
@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
class PostgresMigrationTest {

    private static final Set<String> CORE_TABLES = Set.of("users", "roles", "permissions",
            "user_roles", "role_permissions", "refresh_sessions", "system_settings",
            "ip_access_rules", "audit_events", "announcements", "navigation_menus",
            "maintenance_gc_policies",
            "maintenance_gc_runs", "maintenance_gc_locks", "storage_objects",
            "avatar_candidates", "runtime_component_statuses", "runtime_cache_revisions");

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void liquibaseCreatesCoreTables() {
        List<String> tables = jdbc.queryForList("""
                select table_name
                from information_schema.tables
                where table_schema = 'public'
                """, String.class);

        assertThat(tables).containsAll(CORE_TABLES);
        assertThat(jdbc.queryForObject("select count(*) from databasechangelog", Integer.class))
                .isGreaterThanOrEqualTo(3);

        assertThat(uniqueColumnGroups("users")).contains("username");
        assertThat(uniqueColumnGroups("refresh_sessions")).contains("token_hash");
        assertThat(uniqueColumnGroups("user_roles")).contains("user_id,role_id");
        assertThat(uniqueColumnGroups("role_permissions")).contains("role_id,permission_id");
        assertThat(indexNames()).contains("idx_users_username", "idx_permissions_code",
                "idx_refresh_sessions_token_hash", "idx_audit_events_occurred_at",
                "idx_ip_access_rules_lookup", "idx_announcements_publication",
                "idx_refresh_sessions_user_issued", "idx_refresh_sessions_family",
                "idx_refresh_sessions_expires_at", "idx_maintenance_gc_runs_started_at");
        assertThat(indexNames()).contains("uk_users_avatar_public_id",
                "uk_storage_objects_object_key", "idx_storage_objects_namespace_state",
                "uk_avatar_candidates_public_id", "idx_avatar_candidates_user_expiry",
                "uk_runtime_component_statuses_code", "uk_runtime_cache_revisions_code");
        assertThat(foreignKeyTargets("refresh_sessions")).contains("users");
        assertThat(foreignKeyTargets("announcements")).contains("users");
        assertThat(foreignKeyTargets("role_permissions")).contains("roles", "permissions");
        assertThat(foreignKeyTargets("avatar_candidates")).contains("users", "storage_objects");
        assertThat(foreignKeyTargets("users")).contains("storage_objects");
        assertThat(columnType("users", "id")).containsExactly("character varying", 36);
        assertThat(columnType("users", "status")).containsExactly("character varying", 32);
        assertThat(columnType("users", "avatar_public_id"))
                .containsExactly("character varying", 36);
        assertThat(columnType("users", "avatar_type"))
                .containsExactly("character varying", 32);
        assertThat(columnType("users", "avatar_seed"))
                .containsExactly("character varying", 64);
        assertThat(columnType("announcements", "created_at"))
                .containsExactly("character varying", 64);
        assertThat(columnType("announcements", "published_at"))
                .containsExactly("character varying", 64);
        assertThat(columnType("refresh_sessions", "issued_at"))
                .containsExactly("character varying", 64);
        assertThat(columnType("navigation_menus", "created_at"))
                .containsExactly("character varying", 64);
        assertThat(columnType("navigation_menus", "updated_at"))
                .containsExactly("character varying", 64);
    }

    private List<String> uniqueColumnGroups(String table) {
        return jdbc.queryForList("""
                select string_agg(a.attname, ',' order by key_column.ordinality)
                from pg_catalog.pg_class table_class
                join pg_catalog.pg_namespace namespace
                  on namespace.oid = table_class.relnamespace
                join pg_catalog.pg_index table_index
                  on table_index.indrelid = table_class.oid
                cross join lateral unnest(table_index.indkey)
                  with ordinality as key_column(attnum, ordinality)
                join pg_catalog.pg_attribute a
                  on a.attrelid = table_class.oid and a.attnum = key_column.attnum
                where namespace.nspname = 'public' and table_class.relname = ?
                  and table_index.indisunique and not table_index.indisprimary
                group by table_index.indexrelid
                """, String.class, table);
    }

    private List<String> indexNames() {
        return jdbc.queryForList("select indexname from pg_indexes where schemaname = 'public'",
                String.class);
    }

    private List<String> foreignKeyTargets(String table) {
        return jdbc.queryForList("""
                select ccu.table_name
                from information_schema.table_constraints tc
                join information_schema.constraint_column_usage ccu
                  on tc.constraint_name = ccu.constraint_name
                 and tc.constraint_schema = ccu.constraint_schema
                where tc.table_schema = 'public' and tc.table_name = ?
                  and tc.constraint_type = 'FOREIGN KEY'
                """, String.class, table);
    }

    private List<Object> columnType(String table, String column) {
        return jdbc.queryForObject("""
                select data_type, character_maximum_length
                from information_schema.columns
                where table_schema = 'public' and table_name = ? and column_name = ?
                """, (resultSet, row) -> List.of(resultSet.getString(1), resultSet.getInt(2)),
                table, column);
    }
}
