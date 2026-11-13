package io.gate.lab.assistant.vulnerable;

import io.gate.lab.assistant.AssistantConfig;
import io.gate.lab.common.LabRepository;
import io.gate.lab.common.MyOrdersController;
import io.gate.prompt.model.AssistantModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 주문 도우미: AI 출력을 그대로 믿고 실행한다 (AI 코딩 도구로 빠르게 만든 기능에 흔한 모양).
 * <ul>
 *   <li>모델이 만든 SQL을 그대로 실행</li>
 *   <li>모델이 만든 HTML을 그대로 화면에 넣도록 돌려줌</li>
 *   <li>모델이 고른 동작을 주인 확인 없이 수행하고, 내부 도구 명령줄로 만들어 넘김 (셸 실행은 흉내만: 문자열로 기록)</li>
 * </ul>
 */
@RestController
@Profile("vulnerable")
public class AssistantController {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AssistantModel model;
    private final JdbcTemplate jdbc;
    private final LabRepository repository;

    public AssistantController(AssistantModel model, JdbcTemplate jdbc, LabRepository repository) {
        this.model = model;
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @PostMapping("/api/assistant/order-search")
    public Map<String, Object> search(Authentication auth, @RequestBody AssistantConfig.Question body) {
        long me = MyOrdersController.current(repository, auth).id();
        String sql = model.orderSql(AssistantConfig.limited(body.question()), me);
        List<Map<String, Object>> rows = jdbc.queryForList(sql);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sql", sql);
        out.put("rows", rows);
        return out;
    }

    @PostMapping("/api/assistant/review-summary")
    public Map<String, Object> summary(Authentication auth, @RequestBody AssistantConfig.ReviewRequest body) {
        MyOrdersController.current(repository, auth);
        if (body.productId() == null || !repository.productExists(body.productId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return Map.of("html", model.reviewSummary(repository.reviewsOf(body.productId())));
    }

    @PostMapping("/api/assistant/order-action")
    public Map<String, Object> action(Authentication auth, @RequestBody AssistantConfig.ActionRequest body) {
        MyOrdersController.current(repository, auth);
        String output = model.orderAction(AssistantConfig.limited(body.request()));
        JsonNode call = JSON.readTree(output);
        String action = call.path("action").asString("");
        long orderId = call.path("args").path("orderId").asLong(0);
        // 내부 도구에 넘기는 명령줄을 문자열로 이어 붙인다. 실제로 셸을 실행하지는 않는다(실습장 안전)
        String shell = "orderctl " + action + " " + orderId;
        String result = "nothing to do";
        if (action.startsWith("cancel_order")) {
            repository.deleteOrder(orderId); // 누구의 주문인지 확인하지 않는다
            result = "order " + orderId + " cancelled";
        } else if (action.startsWith("resend_receipt")) {
            result = "receipt for order " + orderId + " sent";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("result", result);
        out.put("shellCommand", shell);
        out.put("simulated", true);
        return out;
    }
}
