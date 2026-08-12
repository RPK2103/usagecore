package io.usagecore.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.usagecore.controlplane.support.TestSecurityConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@Import(TestSecurityConfiguration.class)
class ControlPlaneApplicationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("usagecore")
            .withUsername("usagecore")
            .withPassword("usagecore");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost/unused");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsWithPostgresAndFlyway() {
        assertThat(postgres.isRunning()).isTrue();

        Integer connectionCheck = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(connectionCheck).isEqualTo(1);

        List<Map<String, Object>> migrations = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank"
        );
        assertThat(migrations).isNotEmpty();
        assertThat(migrations).extracting(row -> row.get("version")).contains("1", "2", "3", "4");
        assertThat(migrations).allMatch(row -> Boolean.TRUE.equals(row.get("success")));
    }
}
