package io.gate.lab.common;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 내 정보와 내 주문 목록·생성 (두 버전 공통). */
@RestController
public class MyOrdersController {

    public record OrderRequest(Long productId, Integer quantity, String note, String shippingAddress) {
    }

    private final LabRepository repository;

    public MyOrdersController(LabRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(Authentication auth) {
        LabRepository.User u = current(repository, auth);
        return Map.of("id", u.id(), "username", u.username(), "displayName", u.displayName(), "email", u.email(),
                "role", u.role());
    }

    @GetMapping("/api/orders")
    public List<Map<String, Object>> myOrders(Authentication auth) {
        return repository.ordersOf(current(repository, auth).id()).stream().map(LabRepository::view).toList();
    }

    @PostMapping("/api/orders")
    public ResponseEntity<Map<String, Object>> create(Authentication auth, @RequestBody OrderRequest req) {
        validate(repository, req);
        long id = repository.createOrder(current(repository, auth).id(), req.productId(), req.quantity(),
                text(req.note()), text(req.shippingAddress()));
        return ResponseEntity.status(HttpStatus.CREATED).body(LabRepository.view(repository.findOrder(id).orElseThrow()));
    }

    /** 인증된 사용자 이름(토큰의 sub)으로 사용자를 찾는다. */
    public static LabRepository.User current(LabRepository repository, Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return repository.findUser(auth.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public static void validate(LabRepository repository, OrderRequest req) {
        if (req == null || req.productId() == null || req.quantity() == null || req.quantity() < 1
                || req.quantity() > 100 || !repository.productExists(req.productId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid order");
        }
        if (text(req.note()).length() > 500 || text(req.shippingAddress()).length() > 300) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text too long");
        }
    }

    public static String text(String s) {
        return s == null ? "" : s;
    }
}
