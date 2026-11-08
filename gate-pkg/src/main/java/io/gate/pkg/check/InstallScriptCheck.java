package io.gate.pkg.check;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Severity;
import io.gate.pkg.registry.PackageInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PKG-SCRIPT: 설치하는 순간 코드가 실행되는 패키지.
 * <ul>
 *   <li>npm: preinstall·install·postinstall 스크립트(또는 binding.gyp) → WARN·MEDIUM</li>
 *   <li>PyPI: wheel 없이 sdist만 있음(설치 시 setup.py 실행 가능) → WARN·LOW</li>
 * </ul>
 * 설치 스크립트 자체는 흔하다(esbuild 등). 그래서 단독으로는 경고만 하고,
 * 다른 위험 신호와 겹칠 때 PKG-COMBO(2주차)에서 차단한다.
 */
public final class InstallScriptCheck implements Check {

    static final int MAX_SCRIPT_CHARS = 200;

    @Override
    public List<Finding> evaluate(CheckContext ctx) {
        PackageInfo info = ctx.info();
        if (info.versionMissing()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        if (!info.installScripts().isEmpty()) {
            Map<String, Object> scripts = new LinkedHashMap<>();
            info.installScripts().forEach((hook, script) -> scripts.put(hook, truncate(script)));
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("scripts", scripts);
            findings.add(Rules.finding(ctx.runId(), Rules.SCRIPT,
                    "'" + info.name() + "@" + info.resolvedVersion() + "' runs code on install ("
                            + String.join(", ", info.installScripts().keySet()) + ")",
                    Severity.MEDIUM, Decision.WARN, ctx.location(), evidence, ctx.now()));
        }
        if (info.sdistOnly()) {
            findings.add(Rules.finding(ctx.runId(), Rules.SCRIPT,
                    "'" + info.name() + "==" + info.resolvedVersion() + "' has no wheel; installing builds from source",
                    Severity.LOW, Decision.WARN, ctx.location(),
                    Map.of("distribution", "sdist only"), ctx.now()));
        }
        return findings;
    }

    private static String truncate(String script) {
        return script.length() <= MAX_SCRIPT_CHARS ? script : script.substring(0, MAX_SCRIPT_CHARS) + "…";
    }
}
