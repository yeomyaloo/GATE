package io.gate.pkg.scan;

import io.gate.common.finding.Decision;
import io.gate.common.finding.Finding;
import io.gate.common.finding.Location;
import io.gate.pkg.check.Rules;
import io.gate.pkg.check.VulnCheck;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.PyPiRegistryClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 오탐 예외 파일 {@code .gate/pkg-allow.txt} (설계안 8장, 확정 결정 3번).
 *
 * <pre>
 * # 형식: 생태계:이름@버전  # 이유 (버전과 이유는 필수)
 * npm:preact-compat@3.19.0   # 2026-11-10 hjyeom: 검토함, react 호환 레이어
 * pypi:requests-mock@1.12.1  # 테스트 전용
 * </pre>
 *
 * <ul>
 *   <li>버전을 고정해야 한다. 새 버전이 나오면 다시 검사받는다</li>
 *   <li>예외로 처리한 결과는 지우지 않고 PASS로 바꿔 근거에 {@code allowedBy}를 남긴다</li>
 *   <li><b>풀 수 없는 결과:</b> 레지스트리에 없는 패키지(PKG-EXIST), OSV 악성 패키지 보고. 이 둘은 검토해서
 *       괜찮아질 수 있는 성격이 아니다</li>
 * </ul>
 */
public final class AllowList {

    public static final String RELATIVE_PATH = ".gate/pkg-allow.txt";

    /** 예외 한 줄. */
    public record Entry(Ecosystem ecosystem, String name, String version, String reason, int line) {
    }

    private final List<Entry> entries;
    private final List<String> problems;
    private final String source;

    private AllowList(List<Entry> entries, List<String> problems, String source) {
        this.entries = List.copyOf(entries);
        this.problems = List.copyOf(problems);
        this.source = source;
    }

    public static AllowList empty() {
        return new AllowList(List.of(), List.of(), RELATIVE_PATH);
    }

    /** 프로젝트 폴더의 {@code .gate/pkg-allow.txt}를 읽는다. 없으면 빈 목록. */
    public static AllowList load(Path projectDir) {
        if (projectDir == null) {
            return empty();
        }
        Path file = projectDir.resolve(RELATIVE_PATH);
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new AllowList(List.of(), List.of("could not read " + RELATIVE_PATH + ": " + e.getMessage()),
                    RELATIVE_PATH);
        }
    }

    public static AllowList parse(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int hash = line.indexOf('#');
            String spec = (hash >= 0 ? line.substring(0, hash) : line).strip();
            String reason = hash >= 0 ? line.substring(hash + 1).strip() : "";
            int colon = spec.indexOf(':');
            int at = spec.lastIndexOf('@');
            Ecosystem eco = colon > 0 ? ecosystem(spec.substring(0, colon)) : null;
            if (eco == null || at <= colon + 1 || at == spec.length() - 1 || reason.isEmpty()) {
                problems.add(RELATIVE_PATH + " line " + (i + 1) + " ignored: expected 'ecosystem:name@version  # "
                        + "reason' (version and reason are required): " + line);
                continue;
            }
            entries.add(new Entry(eco, normalize(eco, spec.substring(colon + 1, at)), spec.substring(at + 1).strip(),
                    reason, i + 1));
        }
        return new AllowList(entries, problems, RELATIVE_PATH);
    }

    public List<String> problems() {
        return problems;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** 예외 목록을 적용한 판정. 맞는 줄이 없으면 그대로 돌려준다. */
    public PackageVerdict apply(PackageVerdict verdict) {
        if (verdict.decision() == Decision.PASS || verdict.findings().isEmpty()) {
            return verdict;
        }
        Optional<Entry> entry = match(verdict);
        if (entry.isEmpty()) {
            return verdict;
        }
        List<Finding> out = new ArrayList<>();
        for (Finding f : verdict.findings()) {
            if (f.decision() == Decision.PASS || !overridable(f)) {
                out.add(f);
                continue;
            }
            Map<String, Object> evidence = new LinkedHashMap<>(f.evidence());
            evidence.put("allowedBy", source + ":" + entry.get().line() + " (" + entry.get().reason() + ")");
            evidence.put("originalDecision", f.decision().name());
            out.add(new Finding(f.id(), f.runId(), f.ruleId(), f.title(), f.severity(), Decision.PASS, f.location(),
                    evidence, f.source(), f.detectedAt()));
        }
        return PackageVerdict.of(verdict.request(), verdict.checkedVersion(), out);
    }

    /** 레지스트리에 없는 패키지와 악성 패키지 보고는 예외로 풀 수 없다. */
    public static boolean overridable(Finding f) {
        if (f.ruleId().equals(Rules.EXIST)) {
            return false;
        }
        return !(f.ruleId().equals(Rules.VULN) && Boolean.TRUE.equals(f.evidence().get(VulnCheck.MALICIOUS_KEY)));
    }

    private Optional<Entry> match(PackageVerdict verdict) {
        Location location = verdict.findings().get(0).location();
        if (!(location instanceof Location.Package pkg)) {
            return Optional.empty();
        }
        String version = verdict.checkedVersion() != null ? verdict.checkedVersion() : pkg.version();
        if (version == null) {
            return Optional.empty();
        }
        Ecosystem eco = verdict.request().ecosystem();
        String name = normalize(eco, pkg.name());
        return entries.stream()
                .filter(e -> e.ecosystem() == eco && e.name().equals(name) && e.version().equals(version))
                .findFirst();
    }

    private static Ecosystem ecosystem(String id) {
        for (Ecosystem e : Ecosystem.values()) {
            if (e.id().equalsIgnoreCase(id.strip())) {
                return e;
            }
        }
        return null;
    }

    private static String normalize(Ecosystem eco, String name) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        return eco == Ecosystem.PYPI ? PyPiRegistryClient.normalize(n) : n;
    }
}
