package io.gate.pkg.cli;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.pkg.manifest.DependencyDiff;
import io.gate.pkg.scan.PackageVerdict;
import io.gate.pkg.scan.ScanReport;
import java.util.List;
import java.util.Map;

/** GitHub Actions용 출력: 워크플로 명령(주석)과 Job Summary 마크다운. */
public final class GitHubOutput {

    private GitHubOutput() {
    }

    /** BLOCK은 error, WARN은 warning 주석. 해당 매니페스트 파일·줄에 붙는다. */
    public static String annotations(ScanReport report, Map<PackageVerdict, DependencyDiff.Change> where) {
        StringBuilder sb = new StringBuilder();
        for (PackageVerdict v : report.packages()) {
            if (v.decision() == Decision.PASS) {
                continue;
            }
            DependencyDiff.Change c = where.get(v);
            String level = v.decision() == Decision.BLOCK ? "error" : "warning";
            String title = "GATE " + v.decision() + " " + v.request().ecosystem().id() + ":" + v.request().name();
            StringBuilder msg = new StringBuilder();
            for (Finding f : v.findings()) {
                if (f.decision() != Decision.PASS) {
                    msg.append(f.ruleId()).append(" (").append(f.severity()).append("): ").append(f.title()).append('\n');
                }
            }
            sb.append("::").append(level).append(' ');
            if (c != null) {
                sb.append("file=").append(property(c.file())).append(",line=").append(c.line()).append(',');
            }
            sb.append("title=").append(property(title)).append("::").append(data(msg.toString().strip()))
                    .append('\n');
        }
        return sb.toString();
    }

    /** Job Summary 마크다운 표. */
    public static String summary(ScanReport report, Map<PackageVerdict, DependencyDiff.Change> where,
            List<String> unchecked, List<String> manifests) {
        StringBuilder sb = new StringBuilder("## GATE gate-pkg: dependency check\n\n");
        if (report.packages().isEmpty() && unchecked.isEmpty()) {
            sb.append("No new or changed dependencies")
                    .append(manifests.isEmpty() ? " (no manifest changed)." : " in " + String.join(", ", manifests) + ".")
                    .append('\n');
            return sb.toString();
        }
        sb.append("**Result: ").append(report.decision()).append("**\n\n");
        sb.append("| Decision | Package | Change | File | Findings |\n|---|---|---|---|---|\n");
        for (PackageVerdict v : report.packages()) {
            DependencyDiff.Change c = where.get(v);
            String after = c == null || c.after().isBlank() ? "(any version)" : c.after();
            String change = c == null ? "" : (c.before() == null ? "added " : c.before() + " → ") + after;
            StringBuilder findings = new StringBuilder();
            for (Finding f : v.findings()) {
                if (findings.length() > 0) {
                    findings.append("<br>");
                }
                findings.append('`').append(f.ruleId()).append("` ").append(cell(f.title()));
                if (f.decision() == Decision.PASS && f.evidence().containsKey("allowedBy")) {
                    findings.append(" _(allowed)_");
                }
            }
            sb.append("| ").append(icon(v.decision())).append(' ').append(v.decision())
                    .append(" | `").append(v.request().ecosystem().id()).append(':').append(v.request().name())
                    .append(v.checkedVersion() == null ? "" : "@" + v.checkedVersion()).append('`')
                    .append(" | ").append(cell(change))
                    .append(" | ").append(c == null ? "" : cell(c.file() + ":" + c.line()))
                    .append(" | ").append(findings.length() == 0 ? "—" : findings).append(" |\n");
        }
        if (!unchecked.isEmpty()) {
            sb.append("\n**Not checked**\n\n");
            unchecked.forEach(u -> sb.append("- ").append(cell(u)).append('\n'));
        }
        return sb.toString();
    }

    private static String icon(Decision d) {
        switch (d) {
            case BLOCK:
                return "⛔";
            case WARN:
                return "⚠️";
            default:
                return "✅";
        }
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    /** 워크플로 명령의 메시지 부분 이스케이프. */
    static String data(String s) {
        return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    /** 워크플로 명령의 속성 값 이스케이프 (콜론·쉼표 포함). */
    static String property(String s) {
        return data(s).replace(":", "%3A").replace(",", "%2C");
    }
}
