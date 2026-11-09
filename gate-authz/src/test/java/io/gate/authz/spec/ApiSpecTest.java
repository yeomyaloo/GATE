package io.gate.authz.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ApiSpecTest {

    /** 실습장의 실제 명세 (gate-lab의 OpenApiContract 테스트가 컨트롤러와 일치를 보장). */
    static String labSpec() throws Exception {
        return Files.readString(Path.of("..", "gate-lab", "src", "main", "resources", "static", "openapi.yaml"));
    }

    @Test
    void readsLabOperationsAndAuthRequirements() throws Exception {
        ApiSpec spec = ApiSpec.parse(labSpec());
        assertEquals(8, spec.operations().size());
        assertFalse(spec.find("POST", "/api/auth/login").orElseThrow().requiresAuth(), "security: [] overrides global");
        assertFalse(spec.find("GET", "/api/products/search").orElseThrow().requiresAuth());
        assertTrue(spec.find("GET", "/api/orders/{id}").orElseThrow().requiresAuth(), "global bearerAuth applies");
        assertEquals(List.of("id"), spec.find("DELETE", "/api/orders/{id}").orElseThrow().pathParams());
    }

    @Test
    void groupsItemOperationsWithCreateAndList() throws Exception {
        List<ApiSpec.Resource> resources = ApiSpec.parse(labSpec()).resources();
        assertEquals(1, resources.size());
        ApiSpec.Resource orders = resources.get(0);
        assertEquals("/api/orders", orders.collectionPath());
        assertEquals("id", orders.idParam());
        assertTrue(orders.create().isPresent() && orders.list().isPresent());
        assertEquals(List.of("GET", "PUT", "DELETE"),
                orders.itemOperations().stream().map(ApiSpec.Operation::method).toList());
    }

    @Test
    void resolvesRefsInRequestBody() throws Exception {
        JsonNode schema = ApiSpec.parse(labSpec()).find("POST", "/api/orders").orElseThrow().requestSchema();
        assertTrue(schema.path("properties").has("productId"), schema.toString());
    }

    @Test
    void jsonSpecAndNestedParamsAreHandled() {
        ApiSpec spec = ApiSpec.parse("""
                {"openapi":"3.0.3","paths":{
                  "/users/{uid}/notes/{nid}":{"get":{}},
                  "/notes/{id}":{"get":{"security":[{}]},"patch":{}},
                  "/notes":{"get":{}}}}""");
        assertEquals(1, spec.resources().size(), "paths with a parameter before the last segment are skipped");
        assertFalse(spec.find("GET", "/notes/{id}").orElseThrow().requiresAuth(), "[{}] means anonymous allowed");
        assertTrue(spec.resources().get(0).create().isEmpty());
    }

    @Test
    void rejectsNonOpenApiAndExternalRefs() {
        assertThrows(IllegalArgumentException.class, () -> ApiSpec.parse("swagger: '2.0'\npaths: {}"));
        assertThrows(IllegalArgumentException.class, () -> ApiSpec.parse("a: 1"));
        ApiSpec spec = ApiSpec.parse("""
                openapi: 3.0.0
                paths:
                  /x:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: { $ref: 'https://example.invalid/s.json' }
                """.replace("paths:", "paths:")
                .replace("schema: { $ref: 'https://example.invalid/s.json' }", "schema: { type: object }"));
        assertEquals(1, spec.operations().size());
        assertThrows(IllegalArgumentException.class,
                () -> spec.resolve(new tools.jackson.databind.json.JsonMapper().readTree(
                        "{\"$ref\":\"https://example.invalid/s.json\"}"), 0));
    }

    @Test
    void bodyGeneratorUsesExamplesThenRequiredDefaults() throws Exception {
        ApiSpec spec = ApiSpec.parse(labSpec());
        JsonNode body = new BodyGenerator(spec).generate(
                spec.find("POST", "/api/orders").orElseThrow().requestSchema());
        assertEquals(1, body.path("productId").asInt());
        assertEquals(1, body.path("quantity").asInt());
        assertTrue(body.has("note"), "properties with examples are included");

        ApiSpec plain = ApiSpec.parse("""
                openapi: 3.0.0
                paths: {}
                components:
                  schemas:
                    S:
                      type: object
                      required: [n, s, b, nested]
                      properties:
                        n: { type: integer, minimum: 5 }
                        s: { type: string }
                        b: { type: boolean }
                        optional: { type: string }
                        nested: { type: object, required: [k], properties: { k: { type: string, enum: [x, y] } } }
                """);
        JsonNode generated = new BodyGenerator(plain).generate(
                new tools.jackson.databind.json.JsonMapper().readTree("{\"$ref\":\"#/components/schemas/S\"}"));
        assertEquals(5, generated.path("n").asInt());
        assertEquals(BodyGenerator.TEXT, generated.path("s").asString());
        assertFalse(generated.has("optional"));
        assertEquals("x", generated.path("nested").path("k").asString());
    }
}
