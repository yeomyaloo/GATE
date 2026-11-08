package io.gate.pkg.manifest;

import io.gate.pkg.command.InstallCommandParser;
import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.PackageRequest;
import io.gate.pkg.registry.PyPiRegistryClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 의존성 매니페스트에서 직접 의존성을 읽는다 (PR 검사용).
 * <ul>
 *   <li>{@code package.json}: dependencies·devDependencies·optionalDependencies·peerDependencies</li>
 *   <li>{@code requirements*.txt}: 요구사항 줄. {@code -r}·{@code -e}·사설 인덱스 줄은 검사 불가로 기록</li>
 *   <li>{@code pyproject.toml}: PEP 621 {@code [project] dependencies}·optional-dependencies,
 *       Poetry {@code [tool.poetry(.group.*).dependencies]}. 완전한 TOML 파서가 아니라 흔한 형태만 읽는다</li>
 * </ul>
 */
public final class ManifestParser {

    /** 의존성 하나: 키(생태계:정규화 이름), 원래 지정자, 검사 요청. */
    public record Dependency(String key, String spec, PackageRequest request) {
    }

    /** 파일 하나의 결과. 레지스트리 밖 의존성 등은 unchecked에 이유를 남긴다. */
    public record Parsed(Map<String, Dependency> dependencies, List<String> unchecked) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> NPM_SECTIONS = List.of("dependencies", "devDependencies",
            "optionalDependencies", "peerDependencies");
    private static final Pattern TOML_HEADER = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\s*(#.*)?$");
    private static final Pattern TOML_STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|'([^']*)'");
    private static final Pattern POETRY_DEP = Pattern.compile(
            "^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)\\s*=\\s*(.+?)\\s*(#.*)?$");
    private static final Pattern POETRY_VERSION = Pattern.compile("version\\s*=\\s*\"([^\"]*)\"");

    private ManifestParser() {
    }

    public static boolean isManifest(String path) {
        String name = path.replace('\\', '/');
        if (name.contains("node_modules/")) {
            return false;
        }
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.equals("package.json") || name.equals("pyproject.toml")
                || (name.startsWith("requirements") && name.endsWith(".txt"));
    }

    public static Parsed parse(String path, String content) {
        if (content == null || content.isBlank()) {
            return new Parsed(Map.of(), List.of());
        }
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.equals("package.json")) {
            return packageJson(path, content);
        }
        if (name.equals("pyproject.toml")) {
            return pyproject(path, content);
        }
        return requirements(path, content);
    }

    static Parsed packageJson(String path, String content) {
        Map<String, Dependency> deps = new LinkedHashMap<>();
        List<String> unchecked = new ArrayList<>();
        JsonNode root;
        try {
            root = JSON.readTree(content);
        } catch (JacksonException e) {
            return new Parsed(Map.of(), List.of(path + ": not valid JSON, dependencies not checked"));
        }
        for (String section : NPM_SECTIONS) {
            for (Map.Entry<String, JsonNode> e : root.path(section).properties()) {
                String value = e.getValue().asString("");
                if (value.startsWith("workspace:")) {
                    continue; // 같은 저장소 안의 패키지
                }
                Set<PackageRequest> reqs = new LinkedHashSet<>();
                List<String> problems = new ArrayList<>();
                InstallCommandParser.npmSpec(npmSpecString(e.getKey(), value), reqs, problems);
                problems.forEach(p -> unchecked.add(path + ": " + p));
                for (PackageRequest r : reqs) {
                    deps.putIfAbsent(key(r), new Dependency(key(r), value, r));
                }
            }
        }
        return new Parsed(deps, unchecked);
    }

    /** package.json 값은 버전 범위, 태그, 또는 Git·URL·파일 경로다. */
    private static String npmSpecString(String name, String value) {
        String v = value.strip();
        if (v.isEmpty() || v.equals("*")) {
            return name;
        }
        if (v.startsWith("npm:")) {
            return name + "@" + v; // 별칭은 npmSpec이 풀어 준다
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.contains("://") || lower.startsWith("git") || lower.startsWith("file:") || lower.startsWith("link:")
                || lower.startsWith("bitbucket:") || lower.startsWith("gist:") || lower.startsWith(".")
                || lower.startsWith("/") || (v.contains("/") && !v.startsWith("@"))) {
            return v; // 레지스트리 밖 출처: npmSpec이 검사 불가로 기록한다
        }
        return name + "@" + v;
    }

    static Parsed requirements(String path, String content) {
        Map<String, Dependency> deps = new LinkedHashMap<>();
        List<String> unchecked = new ArrayList<>();
        for (String raw : joinContinuations(content)) {
            String line = stripComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("-")) {
                String flag = line.split("[\\s=]", 2)[0];
                if (List.of("-i", "--index-url", "--extra-index-url", "-f", "--find-links").contains(flag)) {
                    unchecked.add(path + ": '" + flag + "' uses a non-default index; results from PyPI would not "
                            + "apply (dependency confusion risk)");
                } else if (List.of("-r", "--requirement", "-c", "--constraint").contains(flag)) {
                    unchecked.add(path + ": nested file '" + line + "' is not followed");
                } else if (List.of("-e", "--editable").contains(flag)) {
                    unchecked.add(path + ": editable install '" + line + "' cannot be checked");
                }
                continue;
            }
            addPypi(path, line, deps, unchecked);
        }
        return new Parsed(deps, unchecked);
    }

    static Parsed pyproject(String path, String content) {
        Map<String, Dependency> deps = new LinkedHashMap<>();
        List<String> unchecked = new ArrayList<>();
        String section = "";
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher h = TOML_HEADER.matcher(line);
            if (h.matches()) {
                section = h.group(1).strip();
                continue;
            }
            boolean pep621Deps = section.equals("project") && line.strip().startsWith("dependencies")
                    && line.contains("=");
            boolean optional = section.equals("project.optional-dependencies") && line.contains("=")
                    && line.contains("[");
            if (pep621Deps || optional) {
                StringBuilder array = new StringBuilder(line.substring(line.indexOf('=') + 1));
                while (!balanced(array) && i + 1 < lines.length) {
                    array.append('\n').append(stripComment(lines[++i]));
                }
                Matcher s = TOML_STRING.matcher(array);
                while (s.find()) {
                    addPypi(path, s.group(1) != null ? s.group(1) : s.group(2), deps, unchecked);
                }
                continue;
            }
            boolean poetry = section.equals("tool.poetry.dependencies") || section.equals("tool.poetry.dev-dependencies")
                    || (section.startsWith("tool.poetry.group.") && section.endsWith(".dependencies"));
            if (poetry) {
                Matcher m = POETRY_DEP.matcher(line);
                if (!m.matches() || m.group(1).equalsIgnoreCase("python")) {
                    continue;
                }
                String value = m.group(2);
                if (value.startsWith("{")) {
                    if (value.contains("git") || value.contains("path") || value.contains("url")) {
                        unchecked.add(path + ": '" + m.group(1) + "' comes from Git, a URL or a local path; it "
                                + "cannot be checked");
                        continue;
                    }
                    Matcher v = POETRY_VERSION.matcher(value);
                    value = v.find() ? v.group(1) : "*";
                } else {
                    value = value.replaceAll("^[\"']|[\"']$", "");
                }
                String spec = value.equals("*") || value.isBlank() ? m.group(1) : m.group(1) + " " + value;
                addPypi(path, spec, deps, unchecked);
            }
        }
        return new Parsed(deps, unchecked);
    }

    private static void addPypi(String path, String spec, Map<String, Dependency> deps, List<String> unchecked) {
        Set<PackageRequest> reqs = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        InstallCommandParser.pypiSpec(spec, reqs, problems);
        problems.forEach(p -> unchecked.add(path + ": " + p));
        for (PackageRequest r : reqs) {
            deps.putIfAbsent(key(r), new Dependency(key(r), r.versionSpec() == null ? "" : r.versionSpec(), r));
        }
    }

    public static String key(PackageRequest r) {
        String name = r.ecosystem() == Ecosystem.PYPI ? PyPiRegistryClient.normalize(r.name())
                : r.name().toLowerCase(Locale.ROOT);
        return r.ecosystem().id() + ":" + name;
    }

    private static boolean balanced(CharSequence s) {
        int depth = 0;
        boolean opened = false;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == quote) {
                    inString = false;
                }
            } else if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '[') {
                depth++;
                opened = true;
            } else if (c == ']') {
                depth--;
            }
        }
        return opened && depth <= 0;
    }

    private static List<String> joinContinuations(String content) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\\R", -1)) {
            if (line.endsWith("\\")) {
                current.append(line, 0, line.length() - 1).append(' ');
            } else {
                out.add(current.append(line).toString());
                current.setLength(0);
            }
        }
        return out;
    }

    private static String stripComment(String line) {
        // requirements에서 '#'은 줄 시작이거나 앞에 공백이 있을 때만 주석이다 (URL의 #egg= 보호)
        int idx = line.startsWith("#") ? 0 : line.indexOf(" #");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
