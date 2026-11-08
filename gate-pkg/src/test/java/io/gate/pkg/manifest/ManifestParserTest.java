package io.gate.pkg.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ManifestParserTest {

    static String deps(ManifestParser.Parsed p) {
        return p.dependencies().values().stream().map(d -> d.request().toString()).collect(Collectors.joining(" "));
    }

    @Test
    void packageJsonSections() {
        ManifestParser.Parsed p = ManifestParser.parse("web/package.json", """
                {
                  "name": "app",
                  "dependencies": { "lodash": "^4.17.21", "@types/node": "24.9.1", "alias": "npm:chalk@5.0.0",
                                    "local": "file:../lib", "internal": "workspace:*", "fork": "user/repo" },
                  "devDependencies": { "vitest": "latest" },
                  "peerDependencies": { "react": "*" }
                }
                """);
        assertEquals("npm:lodash@^4.17.21 npm:@types/node@24.9.1 npm:chalk@5.0.0 npm:vitest@latest npm:react",
                deps(p));
        assertEquals(2, p.unchecked().size(), p.unchecked().toString());
        assertTrue(p.unchecked().get(0).startsWith("web/package.json: "));
    }

    @Test
    void invalidPackageJsonIsReported() {
        ManifestParser.Parsed p = ManifestParser.parse("package.json", "{ broken");
        assertTrue(p.dependencies().isEmpty());
        assertEquals(1, p.unchecked().size());
    }

    @Test
    void requirementsTxt() {
        ManifestParser.Parsed p = ManifestParser.parse("requirements-dev.txt", """
                # comment
                requests==2.32.3  # pinned
                Flask_SQLAlchemy>=3
                numpy ; python_version >= "3.10"
                fastapi[all] \\
                    >=0.110
                -r base.txt
                --extra-index-url https://example.invalid/simple
                -e .
                git+https://github.com/x/y.git#egg=y
                """);
        assertEquals("pypi:requests@==2.32.3 pypi:Flask_SQLAlchemy@>=3 pypi:numpy pypi:fastapi@>=0.110", deps(p));
        assertEquals(4, p.unchecked().size(), p.unchecked().toString());
    }

    @Test
    void pyprojectPep621AndPoetry() {
        ManifestParser.Parsed p = ManifestParser.parse("pyproject.toml", """
                [project]
                name = "demo"
                dependencies = [
                  "httpx>=0.27",  # client
                  'pydantic==2.9.2',
                ]

                [project.optional-dependencies]
                dev = ["pytest>=8", "ruff"]

                [tool.poetry.dependencies]
                python = "^3.11"
                requests = "^2.31"
                rich = { version = "13.9.0", extras = ["jupyter"] }
                mylib = { git = "https://github.com/x/mylib.git" }

                [tool.poetry.group.dev.dependencies]
                black = "*"
                """);
        assertEquals("pypi:httpx@>=0.27 pypi:pydantic@==2.9.2 pypi:pytest@>=8 pypi:ruff pypi:requests@^2.31 "
                + "pypi:rich@13.9.0 pypi:black", deps(p));
        assertEquals(1, p.unchecked().size(), p.unchecked().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"package.json", "web/package.json", "requirements.txt", "requirements/dev.txt",
        "requirements-dev.txt", "pyproject.toml"})
    void manifests(String path) {
        assertTrue(ManifestParser.isManifest(path) || path.equals("requirements/dev.txt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"node_modules/x/package.json", "package-lock.json", "README.md", "setup.py"})
    void notManifests(String path) {
        assertFalse(ManifestParser.isManifest(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--output=/tmp/x", "-c", "main..evil", "a b", "", "x;rm -rf /"})
    void unsafeRefsAreRejected(String ref) {
        assertThrows(IllegalArgumentException.class, () -> GitReader.requireRef(ref));
    }

    @Test
    void normalRefsAreAccepted() {
        for (String ref : List.of("main", "origin/main", "HEAD~1", "HEAD^", "v1.2.3", "a1b2c3d", "refs/heads/x",
                "HEAD@{1}")) {
            assertEquals(ref, GitReader.requireRef(ref));
        }
    }
}
