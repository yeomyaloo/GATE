package io.gate.pkg.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 설치 명령 형태별 표 기반 테스트. 결과는 "생태계:이름@버전" 문자열 목록으로 비교한다. */
class InstallCommandParserTest {

    final InstallCommandParser parser = new InstallCommandParser();

    String packages(String command) {
        return parser.parse(command).packages().stream().map(Object::toString).collect(Collectors.joining(" "));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "npm install lodash                         | npm:lodash",
        "npm i -D typescript@5.6.3 @types/node      | npm:typescript@5.6.3 npm:@types/node",
        "npm add @scope/pkg@^1.2.0                  | npm:@scope/pkg@^1.2.0",
        "npm install --save-dev --prefix web eslint | npm:eslint",
        "npm --prefix web install react             | npm:react",
        "npm i lodash@latest                        | npm:lodash@latest",
        "npm install my-alias@npm:lodash@4.17.21    | npm:lodash@4.17.21",
        "npm isntall lodahs                         | npm:lodahs",
        "cd app && npm i express                    | npm:express",
        "npm test; npm i chalk                      | npm:chalk",
        "sudo -E npm install -g pm2                 | npm:pm2",
        "NODE_ENV=dev npm i debug                   | npm:debug",
        "/usr/local/bin/npm install ms              | npm:ms",
        "npm.cmd install left-pad                   | npm:left-pad",
        "yarn add react react-dom                   | npm:react npm:react-dom",
        "yarn global add serve                      | npm:serve",
        "pnpm add -D vitest                         | npm:vitest",
        "pnpm install zod                           | npm:zod",
        "pip install requests                       | pypi:requests",
        "pip3 install -U 'requests==2.32.3' flask   | pypi:requests@==2.32.3 pypi:flask",
        "pip install \"fastapi[all]>=0.110,<1\"     | pypi:fastapi@>=0.110,<1",
        "python -m pip install --user numpy         | pypi:numpy",
        "python3.12 -m pip install pandas==2.2.0    | pypi:pandas@==2.2.0",
        "py -m pip install reqeusts                 | pypi:reqeusts",
        "pip install --target lib boto3             | pypi:boto3",
        "pip install 'django; python_version>\"3.8\"' | pypi:django",
        "uv add httpx                               | pypi:httpx",
        "uv pip install 'pydantic>=2'               | pypi:pydantic@>=2",
        "poetry add requests@^2.31                  | pypi:requests@^2.31",
        "poetry add --group dev pytest              | pypi:pytest",
        "bash -c \"npm i lodahs\"                   | npm:lodahs",
        "sh -c 'cd x && pip install reqeusts'       | pypi:reqeusts",
        "npm i lodash && npm i lodash               | npm:lodash",
    })
    void extractsPackages(String command, String expected) {
        assertEquals(expected, packages(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "git status", "ls -la", "./gradlew build", "npm test", "npm run build", "npm --version", "yarn --version",
        "pip list", "pip show requests", "pip --version", "python script.py", "echo npm install lodash",
        "grep -r 'npm install' docs", "uv sync --help", "curl -s https://registry.npmjs.org/lodash",
    })
    void nonInstallCommandsAreIgnored(String command) {
        ParsedCommand p = parser.parse(command);
        assertFalse(p.isInstall(), () -> command + " -> " + p);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "npm install", "npm i", "npm ci", "yarn", "yarn install", "pnpm install", "poetry install",
        "pip install -r requirements.txt", "pip install -rrequirements.txt", "pip install -e .",
        "pip install --index-url https://example.invalid/simple foo",
        "pip install --extra-index-url https://example.invalid/simple foo",
        "npm install --registry https://example.invalid lodash",
        "npm install github:user/repo", "npm install user/repo", "npm install ./local-pkg",
        "npm install https://example.invalid/x.tgz", "pip install git+https://github.com/x/y.git",
        "pip install ./dist/thing-1.0-py3-none-any.whl", "pip install 'pkg @ https://example.invalid/pkg.zip'",
        "npm install $PKG", "pip install $(cat names.txt)", "npm i `echo lodash`",
        "eval \"npm install $X\"",
    })
    void installsThatCannotBeCheckedAreReported(String command) {
        ParsedCommand p = parser.parse(command);
        assertTrue(p.isInstall(), command);
        assertFalse(p.unchecked().isEmpty(), () -> command + " -> " + p);
    }

    @Test
    void registryPackagesAreStillCheckedNextToUncheckedParts() {
        ParsedCommand p = parser.parse("pip install -r req.txt lodash-py && npm install github:a/b chalk");
        assertEquals(List.of("pypi:lodash-py", "npm:chalk"), p.packages().stream().map(Object::toString).toList());
        assertEquals(2, p.unchecked().size());
    }

    @Test
    void quotedSeparatorsAreNotSplit() {
        assertEquals("", packages("echo 'a && npm i lodash'"));
        assertEquals("", packages("git commit -m \"npm install x; done\""));
    }

    @Test
    void orOperatorSplitsCommands() {
        assertEquals("npm:chalk", packages("npm test || npm i chalk || echo failed"));
    }

    @Test
    void redirectionsAreNotArguments() {
        assertEquals("npm:lodash", packages("npm install lodash 2>&1 | tail -5"));
        assertEquals("npm:lodash", packages("npm install lodash > out.txt 2>/dev/null"));
        assertEquals("pypi:flask", packages("pip install flask >> log.txt"));
    }

    @Test
    void lineContinuation() {
        assertEquals("npm:a npm:b", packages("npm install \\\n  a \\\n  b"));
    }

    @Test
    void splitterMarksDynamicSegments() {
        List<ShellSplitter.Segment> s = ShellSplitter.split("echo '$HOME' && npm i \"$X\"");
        assertFalse(s.get(0).dynamic(), "single quotes are literal");
        assertTrue(s.get(1).dynamic());
    }

    // ---------- 받아서 바로 실행 (npx 계열, 1단계 한계 보완 2주차) ----------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "npx -y @modelcontextprotocol/server-filesystem /tmp     | npm:@modelcontextprotocol/server-filesystem",
        "npx cowsay hello world                                    | npm:cowsay",
        "npx create-vite@5.5.2 my-app --template react             | npm:create-vite@5.5.2",
        "npx --yes lodahs                                          | npm:lodahs",
        "npx -p typescript@5.6.3 tsc --version                     | npm:typescript@5.6.3",
        "npx --package=@angular/cli ng new app                     | npm:@angular/cli",
        "npx -p a-pkg -p b-pkg some-cmd                            | npm:a-pkg npm:b-pkg",
        "npm exec -- prettier --write .                            | npm:prettier",
        "npm exec --package=eslint@9 -- eslint src                 | npm:eslint@9",
        "npm x serve                                               | npm:serve",
        "pnpm dlx create-next-app@latest web                       | npm:create-next-app@latest",
        "yarn dlx -p cowsay cowsay hi                              | npm:cowsay",
        "uvx mcp-server-fetch                                      | pypi:mcp-server-fetch",
        "uvx ruff@0.7.0 check .                                    | pypi:ruff@==0.7.0",
        "uvx black@latest .                                        | pypi:black",
        "uvx --from 'httpie==3.2.4' http example.invalid           | pypi:httpie@==3.2.4",
        "uvx --with pandas jupyter-core                            | pypi:jupyter-core pypi:pandas",
        "uv tool run reqeusts-cli                                  | pypi:reqeusts-cli",
        "uv tool install ruff                                      | pypi:ruff",
        "pipx run cowsay moo                                       | pypi:cowsay",
        "pipx run --spec 'black==24.10.0' black .                  | pypi:black@==24.10.0",
        "pipx install poetry                                       | pypi:poetry",
        "npx.cmd -y some-mcp-server                                | npm:some-mcp-server",
        "bash -c 'npx -y evil-mcp'                                 | npm:evil-mcp",
    })
    void runAndExecuteCommandsAreChecked(String command, String expected) {
        assertEquals(expected, packages(command));
    }

    @Test
    void argumentsAfterTheRunPackageAreNotPackages() {
        // npx 뒤의 인자(파일명·옵션)는 실행할 프로그램의 인자다
        assertEquals("npm:cowsay", packages("npx cowsay lodahs reqeusts"));
        assertEquals("npm:tsx", packages("npx tsx script.ts --watch"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"npx --registry https://example.invalid foo", "npx github:user/repo",
        "uvx --index-url https://example.invalid/simple tool", "npx ./local-bin"})
    void runCommandsFromOtherSourcesAreReported(String command) {
        ParsedCommand p = parser.parse(command);
        assertTrue(p.isInstall(), command);
        assertFalse(p.unchecked().isEmpty(), () -> command + " -> " + p);
    }

    @ParameterizedTest
    @ValueSource(strings = {"npx", "npm exec", "uv run script.py", "pipx list", "uv tool list", "npm x --help"})
    void runCommandsWithoutAPackageAreIgnored(String command) {
        assertEquals("", packages(command), command);
    }
}
