package io.gate.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 0단계 완료 기준 검증: 실제 PostgreSQL·Redis 컨테이너를 띄우고 헬스체크와 스키마를 확인한다.
 * Docker가 없는 환경에서는 Testcontainers가 이 클래스를 비활성화한다(disabledWithoutDocker).
 * 이미지는 docker-compose.yml과 같은 digest로 고정한다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HealthCheckIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18.6-alpine3.24@sha256:123b17998ccaf47e53b502e8c6a1e79afd8f579c6f3eec83c563da026f957395")
            .asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse(
            "redis:8.8.4-alpine3.23@sha256:c027ae0f677dcf73d74038256f5b88c9ba7b11ccbfae97e96a9f091208d36125"))
            .withExposedPorts(6379);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthIsUpWithDbAndRedis() {
        String body = RestClient.create("http://localhost:" + port)
                .get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(body).contains("\"status\":\"UP\"").contains("\"db\"").contains("\"redis\"");
    }

    @Test
    void flywayCreatedResultTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("scan_run", "finding");
    }
}
