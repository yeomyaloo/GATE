package io.gate.pkg.command;

import io.gate.pkg.registry.Ecosystem;
import io.gate.pkg.registry.PackageRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 셸 명령에서 패키지 설치를 찾는다 (설계안 1장 범위).
 * <ul>
 *   <li>npm: {@code npm install|i|add|…}, {@code yarn add}, {@code yarn global add}, {@code pnpm add|install}</li>
 *   <li>PyPI: {@code pip|pip3 install}, {@code python -m pip install}, {@code uv add}, {@code uv pip install},
 *       {@code poetry add}</li>
 *   <li>앞에 붙는 {@code sudo}, {@code env}, {@code VAR=값}, {@code time} 등은 건너뛰고,
 *       {@code bash -c "…"}는 안쪽 명령을 다시 해석한다</li>
 * </ul>
 * 원칙: 설치 명령인데 무엇이 설치될지 확정할 수 없으면 {@link ParsedCommand#unchecked()}에 이유를 남긴다.
 * 모르는 것을 통과로 처리하지 않는다.
 */
public final class InstallCommandParser {

    private static final Set<String> NPM_INSTALL = Set.of("install", "i", "add", "in", "ins", "inst", "insta",
            "instal", "isnt", "isnta", "isntal", "isntall");
    private static final Set<String> NPM_MANIFEST = Set.of("ci", "clean-install", "install-clean", "isntall-clean");
    private static final Set<String> NPM_VALUE_FLAGS = Set.of("--prefix", "-C", "-w", "--workspace", "--tag",
            "--cache", "--userconfig", "--globalconfig", "--omit", "--include", "--install-strategy", "--before",
            "--loglevel", "--registry", "--filter", "-F", "--dir", "--cwd", "--modules-folder", "--network-concurrency");
    private static final Set<String> PIP_VALUE_FLAGS = Set.of("-c", "--constraint", "-t", "--target", "--prefix",
            "--root", "--src", "--platform", "--python-version", "--implementation", "--abi", "--progress-bar", "--log",
            "--cache-dir", "--upgrade-strategy", "--global-option", "--config-settings", "-C", "--only-binary",
            "--no-binary", "--python", "-p", "--group", "--optional", "--package", "--directory", "--project",
            "--extras", "-E", "-G", "--markers", "--timeout", "--retries", "--trusted-host", "--proxy", "--cert",
            "--client-cert", "--exists-action", "--report", "--root-user-action", "--keyring-provider");
    /** 값이 있는 옵션 중 "공개 레지스트리 밖에서 받는다"는 뜻이라 검사 결과를 믿을 수 없게 만드는 것. */
    private static final Set<String> CUSTOM_SOURCE_FLAGS = Set.of("--registry", "-i", "--index-url",
            "--extra-index-url", "-f", "--find-links", "--index", "--default-index", "--source");
    private static final Set<String> PREFIX_COMMANDS = Set.of("sudo", "env", "time", "command", "nohup", "exec",
            "doas", "nice");
    private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "dash", "ksh");

    private static final Pattern ENV_ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*");
    private static final Pattern PYTHON = Pattern.compile("^(python|python[0-9][0-9.]*|py|pypy[0-9.]*)$");
    private static final Pattern PIP = Pattern.compile("^pip[0-9.]*$");
    private static final Pattern PEP508 = Pattern.compile(
            "^([A-Za-z0-9][A-Za-z0-9._-]*)\\s*(\\[[^\\]]*\\])?\\s*(.*)$");
    private static final Pattern REDIRECT = Pattern.compile("^[0-9]*(?:>>?|<|&>>?|>\\|)(.*)$");
    private static final Pattern GITHUB_SHORTHAND = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(#.*)?$");
    /** 해석하지 못한 조각이라도 이렇게 생겼으면 설치 명령일 수 있다고 본다. */
    private static final Pattern INSTALL_LIKE = Pattern.compile(
            "\\b(npm|yarn|pnpm|pip[0-9.]*|uv|poetry)\\b.*\\b(install|add|i|ci)\\b", Pattern.CASE_INSENSITIVE);

    private static final int MAX_DEPTH = 3;

    public ParsedCommand parse(String command) {
        Set<PackageRequest> packages = new LinkedHashSet<>();
        List<String> unchecked = new ArrayList<>();
        parseInto(command, packages, unchecked, 0);
        return new ParsedCommand(List.copyOf(packages), unchecked);
    }

    private void parseInto(String command, Set<PackageRequest> packages, List<String> unchecked, int depth) {
        for (ShellSplitter.Segment segment : ShellSplitter.split(command)) {
            List<String> tokens = stripPrefixes(segment.tokens());
            if (tokens.isEmpty()) {
                continue;
            }
            String program = programName(tokens.get(0));

            // bash -c "npm i x" → 안쪽 명령을 다시 해석
            if (SHELLS.contains(program) || program.equals("cmd")) {
                int c = indexOfAny(tokens, program.equals("cmd") ? Set.of("/c", "/C", "/k", "/K") : Set.of("-c"));
                if (c > 0 && c + 1 < tokens.size() && depth < MAX_DEPTH) {
                    parseInto(tokens.get(c + 1), packages, unchecked, depth + 1);
                    continue;
                }
            }

            int before = packages.size() + unchecked.size();
            List<String> args = tokens.subList(1, tokens.size());
            switch (program) {
                case "npm":
                    npm(args, packages, unchecked);
                    break;
                case "yarn":
                    yarn(args, packages, unchecked);
                    break;
                case "pnpm":
                    pnpm(args, packages, unchecked);
                    break;
                case "uv":
                    uv(args, packages, unchecked);
                    break;
                case "poetry":
                    poetry(args, packages, unchecked);
                    break;
                default:
                    if (PIP.matcher(program).matches()) {
                        pip(args, packages, unchecked);
                    } else if (PYTHON.matcher(program).matches()) {
                        int m = args.indexOf("-m");
                        if (m >= 0 && m + 1 < args.size() && PIP.matcher(args.get(m + 1)).matches()) {
                            pip(args.subList(m + 2, args.size()), packages, unchecked);
                        }
                    }
            }
            boolean found = packages.size() + unchecked.size() > before;
            if (segment.dynamic() && (found || INSTALL_LIKE.matcher(segment.raw()).find())) {
                unchecked.add("command uses shell expansion ($(...), `...`, $VAR); the real package names are "
                        + "unknown until it runs: " + abbreviate(segment.raw()));
            } else if (!found && (program.equals("eval") || program.equals("xargs"))
                    && INSTALL_LIKE.matcher(segment.raw()).find()) {
                unchecked.add("install command built at runtime (" + program + "): " + abbreviate(segment.raw()));
            }
        }
    }

    // ---------- npm 계열 ----------

    private void npm(List<String> args, Set<PackageRequest> packages, List<String> unchecked) {
        Positionals p = positionals(args, NPM_VALUE_FLAGS, unchecked, "npm");
        if (p.values.isEmpty()) {
            return;
        }
        String sub = p.values.get(0);
        if (NPM_INSTALL.contains(sub)) {
            List<String> specs = p.values.subList(1, p.values.size());
            if (specs.isEmpty()) {
                unchecked.add("'npm " + sub + "' without package names installs everything in package.json; "
                        + "manifest installs are not checked in stage 1");
            }
            specs.forEach(s -> npmSpec(s, packages, unchecked));
        } else if (NPM_MANIFEST.contains(sub)) {
            unchecked.add("'npm " + sub + "' installs from the lockfile; manifest installs are not checked in stage 1");
        }
    }

    private void yarn(List<String> args, Set<PackageRequest> packages, List<String> unchecked) {
        Positionals p = positionals(args, NPM_VALUE_FLAGS, unchecked, "yarn");
        List<String> v = p.values;
        if (v.isEmpty() || v.get(0).equals("install")) {
            if (v.isEmpty() && args.stream().anyMatch(a -> a.startsWith("-"))) {
                return; // yarn --version 같은 옵션만 있는 호출
            }
            unchecked.add("'yarn" + (v.isEmpty() ? "" : " install") + "' installs everything in package.json; "
                    + "manifest installs are not checked in stage 1");
            return;
        }
        int start = v.get(0).equals("add") ? 1 : v.size() > 1 && v.get(0).equals("global") && v.get(1).equals("add")
                ? 2 : -1;
        if (start < 0) {
            return;
        }
        v.subList(start, v.size()).forEach(s -> npmSpec(s, packages, unchecked));
    }

    private void pnpm(List<String> args, Set<PackageRequest> packages, List<String> unchecked) {
        Positionals p = positionals(args, NPM_VALUE_FLAGS, unchecked, "pnpm");
        List<String> v = p.values;
        if (v.isEmpty()) {
            return;
        }
        String sub = v.get(0);
        if (sub.equals("add") || ((sub.equals("install") || sub.equals("i")) && v.size() > 1)) {
            v.subList(1, v.size()).forEach(s -> npmSpec(s, packages, unchecked));
        } else if (sub.equals("install") || sub.equals("i")) {
            unchecked.add("'pnpm " + sub + "' installs everything in package.json; manifest installs are not "
                    + "checked in stage 1");
        }
    }

    /** npm 패키지 지정자: name, name@ver, @scope/name@ver, alias@npm:real@ver. 레지스트리 밖 출처는 검사 불가. */
    public static void npmSpec(String spec, Set<PackageRequest> packages, List<String> unchecked) {
        String s = spec.strip();
        if (s.isEmpty()) {
            return;
        }
        int alias = s.indexOf("@npm:");
        if (alias > 0) {
            s = s.substring(alias + 5);
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("://") || lower.startsWith("git+") || lower.startsWith("git:")
                || lower.startsWith("github:") || lower.startsWith("gitlab:") || lower.startsWith("bitbucket:")
                || lower.startsWith("gist:") || lower.startsWith("file:") || lower.startsWith("link:")
                || lower.startsWith(".") || lower.startsWith("/") || lower.startsWith("~")
                || lower.matches("^[a-z]:[\\\\/].*") || lower.endsWith(".tgz") || lower.endsWith(".tar.gz")
                || (!lower.startsWith("@") && GITHUB_SHORTHAND.matcher(lower).matches())) {
            unchecked.add("'" + spec + "' is not a registry package (URL, Git, or local path); it cannot be checked");
            return;
        }
        int at = s.indexOf('@', s.startsWith("@") ? 1 : 0);
        String name = at > 0 ? s.substring(0, at) : s;
        String version = at > 0 ? s.substring(at + 1) : null;
        packages.add(new PackageRequest(Ecosystem.NPM, name, version));
    }

    // ---------- PyPI 계열 ----------

    private void pip(List<String> args, Set<PackageRequest> packages, List<String> unchecked) {
        int install = args.indexOf("install");
        if (install < 0) {
            return;
        }
        List<String> rest = args.subList(install + 1, args.size());
        for (int i = 0; i < rest.size(); i++) {
            String a = rest.get(i);
            String flag = a.contains("=") && a.startsWith("--") ? a.substring(0, a.indexOf('=')) : a;
            if (flag.equals("-r") || flag.equals("--requirement")) {
                String file = a.contains("=") ? a.substring(a.indexOf('=') + 1) : i + 1 < rest.size() ? rest.get(i + 1)
                        : "?";
                unchecked.add("'pip install -r " + file + "' installs from a requirements file; manifest installs "
                        + "are not checked in stage 1");
                if (!a.contains("=")) {
                    i++;
                }
            } else if (flag.equals("-e") || flag.equals("--editable")) {
                unchecked.add("editable install ('" + a + "') is a local or VCS source; it cannot be checked");
                if (!a.contains("=")) {
                    i++;
                }
            } else if (a.startsWith("-r") && a.length() > 2) {
                unchecked.add("'pip install " + a + "' installs from a requirements file; not checked in stage 1");
            } else if (CUSTOM_SOURCE_FLAGS.contains(flag)) {
                unchecked.add("'" + flag + "' installs from a non-default index; results from PyPI would not "
                        + "apply (dependency confusion risk)");
                if (!a.contains("=")) {
                    i++;
                }
            } else if (PIP_VALUE_FLAGS.contains(flag)) {
                if (!a.contains("=")) {
                    i++;
                }
            } else if (!a.startsWith("-")) {
                pypiSpec(a, packages, unchecked);
            }
        }
    }

    private void uv(List<String> args, Set<PackageRequest> packages, List<String> unchecked) {
        if (args.size() >= 2 && args.get(0).equals("pip")) {
            pip(args.subList(1, args.size()), packages, unchecked);
            return;
        }
        Positionals p = positionals(args, PIP_VALUE_FLAGS, unchecked, "uv");
        if (!p.values.isEmpty() && p.values.get(0).equals("add")) {
            p.values.subList(1, p.values.size()).forEach(s -> pypiSpec(s, packages, unchecked));
        }
    }

    private void poetry(List<String> args, Set<PackageRequest> packages, List<String> unchecked) {
        Positionals p = positionals(args, PIP_VALUE_FLAGS, unchecked, "poetry");
        if (p.values.isEmpty()) {
            return;
        }
        if (p.values.get(0).equals("add")) {
            for (String s : p.values.subList(1, p.values.size())) {
                // poetry는 name@^1.0 형식도 쓴다
                int at = s.indexOf('@');
                String spec = at > 0 && !s.contains("://") ? s.substring(0, at) + " " + s.substring(at + 1) : s;
                pypiSpec(spec, packages, unchecked);
            }
        } else if (p.values.get(0).equals("install")) {
            unchecked.add("'poetry install' installs everything in pyproject.toml/poetry.lock; manifest installs "
                    + "are not checked in stage 1");
        }
    }

    /** PEP 508 요구사항: name, name==1.0, name[extra]>=1, name; 마커. 직접 참조(URL·경로)는 검사 불가. */
    public static void pypiSpec(String spec, Set<PackageRequest> packages, List<String> unchecked) {
        String s = spec.strip();
        int marker = s.indexOf(';');
        if (marker >= 0) {
            s = s.substring(0, marker).strip();
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return;
        }
        if (lower.contains("://") || lower.contains(" @ ") || lower.startsWith("git+") || lower.startsWith(".")
                || lower.startsWith("/") || lower.startsWith("~") || lower.matches("^[a-z]:[\\\\/].*")
                || lower.endsWith(".whl") || lower.endsWith(".zip") || lower.endsWith(".tar.gz")) {
            unchecked.add("'" + spec + "' is not a PyPI package (URL, VCS, or local file); it cannot be checked");
            return;
        }
        Matcher m = PEP508.matcher(s);
        if (!m.matches()) {
            unchecked.add("could not understand requirement '" + spec + "'");
            return;
        }
        String version = m.group(3).strip();
        packages.add(new PackageRequest(Ecosystem.PYPI, m.group(1), version.isEmpty() ? null : version));
    }

    // ---------- 공통 ----------

    private record Positionals(List<String> values) {
    }

    /** 옵션을 건너뛰고 위치 인자만 모은다. 사설 레지스트리 옵션은 검사 불가로 기록한다. */
    private static Positionals positionals(List<String> args, Set<String> valueFlags, List<String> unchecked,
            String tool) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--")) {
                values.addAll(args.subList(i + 1, args.size()));
                break;
            }
            if (a.startsWith("-")) {
                String flag = a.contains("=") ? a.substring(0, a.indexOf('=')) : a;
                if (CUSTOM_SOURCE_FLAGS.contains(flag) && (tool.equals("npm") || tool.equals("yarn")
                        || tool.equals("pnpm") ? flag.equals("--registry") : true)) {
                    unchecked.add("'" + tool + " " + flag + "' uses a non-default registry; results from the public "
                            + "registry would not apply");
                }
                if (!a.contains("=") && valueFlags.contains(flag)) {
                    i++;
                }
                continue;
            }
            values.add(a);
        }
        return new Positionals(values);
    }

    /** 리다이렉트({@code 2>&1}, {@code > out.txt}, {@code 2>/dev/null}, {@code < in})와 그 대상은 인자가 아니다. */
    static List<String> dropRedirections(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Matcher m = REDIRECT.matcher(tokens.get(i));
            if (m.matches()) {
                if (m.group(1).isEmpty()) {
                    i++; // 대상이 다음 토큰에 있다
                }
                continue;
            }
            out.add(tokens.get(i));
        }
        return out;
    }

    private static List<String> stripPrefixes(List<String> rawTokens) {
        List<String> tokens = dropRedirections(rawTokens);
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if (ENV_ASSIGNMENT.matcher(t).matches()) {
                i++;
            } else if (PREFIX_COMMANDS.contains(programName(t))) {
                i++;
                // sudo -u user, env -i 같은 옵션 건너뛰기
                while (i < tokens.size() && tokens.get(i).startsWith("-")) {
                    String opt = tokens.get(i);
                    i += (opt.equals("-u") || opt.equals("-g") || opt.equals("-n")) && i + 1 < tokens.size() ? 2 : 1;
                }
            } else {
                break;
            }
        }
        return tokens.subList(i, tokens.size());
    }

    /** 경로와 Windows 확장자를 뗀 실행 파일 이름. */
    static String programName(String token) {
        String t = token.replace('\\', '/');
        t = t.substring(t.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        for (String ext : List.of(".exe", ".cmd", ".bat", ".ps1")) {
            if (t.endsWith(ext)) {
                return t.substring(0, t.length() - ext.length());
            }
        }
        return t;
    }

    private static int indexOfAny(List<String> tokens, Set<String> wanted) {
        for (int i = 1; i < tokens.size(); i++) {
            if (wanted.contains(tokens.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
