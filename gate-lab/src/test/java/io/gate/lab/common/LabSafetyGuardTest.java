package io.gate.lab.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.gate.lab.LabApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** "외부 공개 금지" 안전장치. */
class LabSafetyGuardTest {

    static LabProperties props(String ack, boolean inContainer) {
        return new LabProperties(ack, "", "", inContainer);
    }

    @Test
    void loopbackWithAckAndOneProfileStarts() {
        assertThat(LabSafetyGuard.verify(props("local-only", false), "127.0.0.1", List.of("vulnerable"), p -> false)).isEqualTo("vulnerable");
        assertThat(LabSafetyGuard.verify(props("local-only", false), "::1", List.of("fixed"), p -> false)).isEqualTo("fixed");
    }

    @Test
    void missingAckRefuses() {
        assertThatThrownBy(() -> LabSafetyGuard.verify(props("", false), "127.0.0.1", List.of("fixed"), p -> false))
                .hasMessageContaining("GATE_LAB_ACK");
        assertThatThrownBy(() -> LabSafetyGuard.verify(props("yes", false), "127.0.0.1", List.of("fixed"), p -> false))
                .hasMessageContaining("GATE_LAB_ACK");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "192.168.0.10", "10.0.0.5", "example.com", "lab.internal"})
    void nonLoopbackAddressRefuses(String address) {
        assertThatThrownBy(() -> LabSafetyGuard.verify(props("local-only", false), address, List.of("fixed"),
                p -> false)).hasMessageContaining("refuses");
    }

    @Test
    void allInterfacesOnlyInsideAContainerWithOptIn() {
        assertThat(LabSafetyGuard.verify(props("local-only", true), "0.0.0.0", List.of("fixed"), p -> true)).isEqualTo("fixed");
        assertThatThrownBy(() -> LabSafetyGuard.verify(props("local-only", true), "0.0.0.0", List.of("fixed"),
                p -> false)).as("not actually in a container").hasMessageContaining("refuses");
        assertThatThrownBy(() -> LabSafetyGuard.verify(props("local-only", false), "0.0.0.0", List.of("fixed"),
                p -> true)).as("no opt-in").hasMessageContaining("refuses");
    }

    @Test
    void exactlyOneVariantProfile() {
        assertThatThrownBy(() -> LabSafetyGuard.verify(props("local-only", false), "127.0.0.1", List.of(),
                p -> false)).hasMessageContaining("exactly one profile");
        assertThatThrownBy(() -> LabSafetyGuard.verify(props("local-only", false), "127.0.0.1",
                List.of("vulnerable", "fixed"), p -> false)).hasMessageContaining("exactly one profile");
    }

    @Test
    void applicationDoesNotStartOnAPublicAddress() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(LabApplication.class)
                .profiles("vulnerable")
                // 명령줄 인자로 넘겨야 application.yml보다 우선한다
                .run("--gate.lab.ack=local-only", "--server.address=0.0.0.0", "--server.port=0").close())
                .hasStackTraceContaining("refuses to listen on 0.0.0.0");
    }

    @Test
    void applicationDoesNotStartWithoutAck() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(LabApplication.class)
                .profiles("fixed")
                .run("--gate.lab.ack=", "--server.port=0").close())
                .hasStackTraceContaining("GATE_LAB_ACK");
    }
}
