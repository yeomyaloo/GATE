package io.gate.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.gate.common.license.License;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LicenseControllerTest {

    @Test
    void noKeyShowsTheNoncommercialNotice() {
        var m = LicenseController.view(new License.Info(License.Status.NONE, null, null, 0, null, List.of()));
        assertThat(m).containsEntry("status", "NONE").containsEntry("license", "PolyForm-Noncommercial-1.0.0")
                .doesNotContainKey("licensee");
        assertThat((String) m.get("notice")).contains("noncommercial");
    }

    @Test
    void validKeyShowsOnlyLicenseeAndExpiry() {
        var m = LicenseController.view(new License.Info(License.Status.VALID, "LIC-1", "Example Corp", 5,
                LocalDate.of(2027, 1, 1), List.of()));
        assertThat(m).containsEntry("licensee", "Example Corp").containsEntry("expiresAt", "2027-01-01")
                .doesNotContainKey("key");
    }
}
