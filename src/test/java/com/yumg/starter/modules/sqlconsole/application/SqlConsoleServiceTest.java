package com.yumg.starter.modules.sqlconsole.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.security.application.AuditService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/sql-console-service-test.db",
        "app.sql-console.enabled=true"
})
@ActiveProfiles("local")
class SqlConsoleServiceTest {
    @Autowired SqlConsoleService live;
    @Test void executesARealLocalSelect() {
        var result = live.execute("select 1 as value", false);
        org.assertj.core.api.Assertions.assertThat(result.rows()).hasSize(1);
    }
    @Test void rejectsExecutionOutsideLocalProfileAndMultipleStatements() {
        StandardEnvironment prod = new StandardEnvironment();
        SqlConsoleService disabled = new SqlConsoleService(Mockito.mock(JdbcTemplate.class), Mockito.mock(AuditService.class), prod, true);
        assertThatThrownBy(() -> disabled.execute("select 1", false)).isInstanceOf(ApiException.class);
        StandardEnvironment local = new StandardEnvironment(); local.setActiveProfiles("local");
        SqlConsoleService enabled = new SqlConsoleService(Mockito.mock(JdbcTemplate.class), Mockito.mock(AuditService.class), local, true);
        assertThatThrownBy(() -> enabled.execute("select 1; select 2", false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> enabled.execute("delete from users", false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> enabled.execute("with removed as (delete from users returning id) select * from removed", false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> enabled.execute("explain analyze delete from users", false)).isInstanceOf(ApiException.class);
    }
}
