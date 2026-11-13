package io.gate.lab.assistant.fixed;

import io.gate.lab.assistant.AssistantConfig;
import io.gate.lab.common.LabRepository;
import io.gate.lab.common.MyOrdersController;
import io.gate.prompt.Blocked;
import io.gate.prompt.GuardResult;
import io.gate.prompt.action.ActionGuard;
import io.gate.prompt.html.HtmlGuard;
import io.gate.prompt.input.InputInspector;
import io.gate.prompt.model.AssistantModel;
import io.gate.prompt.sql.SqlGuard;
import io.gate.prompt.sql.SqlPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 주문 도우미: 같은 모델, 같은 기능이지만 AI 출력이 실행되기 전에 gate-prompt 관문을 거친다.
 * <ul>
 *   <li>SQL: 관문이 허용된 부분만으로 다시 만든 질의를 바인딩 변수로 실행 (본인 주문만)</li>
 *   <li>HTML: 허용 태그만 남긴 HTML을 돌려줌 (지운 것이 있으면 WARN)</li>
 *   <li>동작: 등록된 동작·인자·본인 주문만. 명령줄을 만들지 않고 정해 둔 메서드를 부른다</li>
 *   <li>입력 검사 신호는 막지 않고 응답과 로그에 남긴다 (보조 수단)</li>
 * </ul>
 * 응답의 {@code gate} 항목에 판정(PASS·WARN·BLOCK)과 규칙 ID를 넣는다. 막을 때는 422.
 */
@RestController
@Profile("fixed")
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    static final SqlPolicy ORDERS = new SqlPolicy("orders",
            List.of("id", "product_id", "quantity", "note", "created_at"), "user_id", 50);

    private final AssistantModel model;
    private final JdbcTemplate jdbc;
    private final LabRepository repository;
    private final SqlGuard sqlGuard = new SqlGuard(ORDERS);
    private final ActionGuard actionGuard;

    public AssistantController(AssistantModel model, JdbcTemplate jdbc, LabRepository repository) {
        this.model = model;
        this.jdbc = jdbc;
        this.repository = repository;
        this.actionGuard = new ActionGuard(List.of(
                new ActionGuard.ActionSpec("cancel_order", Map.of("orderId", ActionGuard.ArgType.OWNED_ID)),
                new ActionGuard.ActionSpec("resend_receipt", Map.of("orderId", ActionGuard.ArgType.OWNED_ID)),
                new ActionGuard.ActionSpec("none", Map.of())),
                (user, action, arg, id) -> repository.findOrder(id).map(o -> o.userId() == (long) user)
                        .orElse(false));
    }

    @PostMapping("/api/assistant/order-search")
    public ResponseEntity<Map<String, Object>> search(Authentication auth,
            @RequestBody AssistantConfig.Question body) {
        long me = MyOrdersController.current(repository, auth).id();
        String question = AssistantConfig.limited(body.question());
        List<InputInspector.Signal> signals = InputInspector.inspect(question);
        GuardResult<SqlGuard.BoundQuery> checked = sqlGuard.check(model.orderSql(question, me), me);
        if (checked.rejection().isPresent()) {
            return blocked(checked.rejection().get(), signals);
        }
        SqlGuard.BoundQuery q = checked.allowedValue().orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params().toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("gate", gate(signals.isEmpty() ? "PASS" : "WARN", null, signals, Map.of("executedSql", q.sql())));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/assistant/review-summary")
    public Map<String, Object> summary(Authentication auth, @RequestBody AssistantConfig.ReviewRequest body) {
        MyOrdersController.current(repository, auth);
        if (body.productId() == null || !repository.productExists(body.productId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<String> reviews = repository.reviewsOf(body.productId());
        // 리뷰는 다른 사람이 쓴 글: AI가 읽는 데이터에 숨은 지시(간접 인젝션)도 같은 방법으로 신호를 남긴다
        List<InputInspector.Signal> signals = new ArrayList<>();
        reviews.forEach(r -> InputInspector.inspect(r).stream()
                .filter(s -> signals.stream().noneMatch(x -> x.ruleId().equals(s.ruleId()))).forEach(signals::add));
        HtmlGuard.Result html = HtmlGuard.sanitize(model.reviewSummary(reviews));
        String decision = html.dangerousContentRemoved() || !signals.isEmpty() ? "WARN" : "PASS";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("removedTags", html.removedTags());
        details.put("removedAttributes", html.removedAttributes());
        if (!"PASS".equals(decision)) {
            log.warn("assistant review summary sanitized: product={} removed={} {} signals={}", body.productId(),
                    html.removedTags(), html.removedAttributes(), signals);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("html", html.html());
        out.put("gate", gate(decision, html.dangerousContentRemoved() ? HtmlGuard.REMOVED : null, signals, details));
        return out;
    }

    @PostMapping("/api/assistant/order-action")
    public ResponseEntity<Map<String, Object>> action(Authentication auth,
            @RequestBody AssistantConfig.ActionRequest body) {
        long me = MyOrdersController.current(repository, auth).id();
        String request = AssistantConfig.limited(body.request());
        List<InputInspector.Signal> signals = InputInspector.inspect(request);
        GuardResult<ActionGuard.ActionCall> checked = actionGuard.check(model.orderAction(request), me);
        if (checked.rejection().isPresent()) {
            return blocked(checked.rejection().get(), signals);
        }
        ActionGuard.ActionCall call = checked.allowedValue().orElseThrow();
        String result = switch (call.action()) {
            case "cancel_order" -> {
                long id = (Long) call.args().get("orderId");
                repository.deleteOrder(id);
                yield "order " + id + " cancelled";
            }
            case "resend_receipt" -> "receipt for order " + call.args().get("orderId") + " sent";
            default -> "nothing to do";
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", call.action());
        out.put("result", result);
        out.put("gate", gate(signals.isEmpty() ? "PASS" : "WARN", null, signals, Map.of()));
        return ResponseEntity.ok(out);
    }

    private static ResponseEntity<Map<String, Object>> blocked(Blocked b, List<InputInspector.Signal> signals) {
        log.warn("assistant output blocked: {} {} signals={}", b.ruleId(), b.reason(), signals);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", b.reason());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("gate", gate("BLOCK", b.ruleId(), signals, details)));
    }

    private static Map<String, Object> gate(String decision, String ruleId, List<InputInspector.Signal> signals,
            Map<String, Object> details) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("decision", decision);
        if (ruleId != null) {
            g.put("ruleId", ruleId);
        }
        g.put("inputSignals", signals.stream().map(s -> Map.of("ruleId", s.ruleId(), "excerpt", s.excerpt()))
                .toList());
        g.putAll(details);
        return g;
    }
}
