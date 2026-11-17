package io.gate.server.web;

import io.gate.common.license.License;
import io.gate.common.license.Usage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 라이선스 상태 (docs/design/licensing.md 3장). 시작할 때 로그에 한 줄 남기고, 대시보드 아래쪽에 표시할 상태를 준다.
 * 키 확인은 오프라인이다. 사용 정보는 사용자가 켠 경우에만 시작할 때 한 번 보낸다.
 * 응답에는 상태·회사명·만료일만 넣는다 (키 문자열은 넣지 않는다).
 */
@RestController
public class LicenseController {

    private static final Logger log = LoggerFactory.getLogger(LicenseController.class);
    static final String VERSION = "0.1.0";

    // 생성자는 기본 생성자 하나만 둔다 (여러 개면 Spring이 주입할 생성자를 고르지 못한다)

    @GetMapping("/api/license")
    public Map<String, Object> license() {
        return view(License.current());
    }

    static Map<String, Object> view(License.Info i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", i.status().name());
        m.put("license", "PolyForm-Noncommercial-1.0.0");
        if (i.licensee() != null) {
            m.put("licensee", i.licensee());
            m.put("expiresAt", String.valueOf(i.expiresAt()));
        }
        m.put("notice", i.notice());
        m.put("commercialUrl", License.COMMERCIAL_URL);
        return m;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        License.Info i = License.current();
        log.info(i.notice());
        Usage.report("server", VERSION, i);
    }
}
