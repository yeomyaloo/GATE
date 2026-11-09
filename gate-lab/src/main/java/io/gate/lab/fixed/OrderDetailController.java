package io.gate.lab.fixed;

import io.gate.lab.common.LabRepository;
import io.gate.lab.common.MyOrdersController;
import io.gate.lab.common.MyOrdersController.OrderRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 주문 하나 조회·수정·삭제. 요청한 사용자의 주문만 다룬다.
 * 다른 사람의 주문은 존재 여부도 드러내지 않도록 403이 아니라 404로 답한다.
 */
@RestController
@Profile("fixed")
public class OrderDetailController {

    private final LabRepository repository;

    public OrderDetailController(LabRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/orders/{id}")
    public Map<String, Object> get(Authentication auth, @PathVariable long id) {
        return LabRepository.view(loadOwned(auth, id));
    }

    @PutMapping("/api/orders/{id}")
    public Map<String, Object> update(Authentication auth, @PathVariable long id, @RequestBody OrderRequest req) {
        LabRepository.Order order = loadOwned(auth, id);
        OrderRequest merged = new OrderRequest(order.productId(), req.quantity(), req.note(), req.shippingAddress());
        MyOrdersController.validate(repository, merged);
        repository.updateOrder(id, merged.quantity(), MyOrdersController.text(merged.note()),
                MyOrdersController.text(merged.shippingAddress()));
        return LabRepository.view(loadOwned(auth, id));
    }

    @DeleteMapping("/api/orders/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable long id) {
        loadOwned(auth, id);
        repository.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }

    private LabRepository.Order loadOwned(Authentication auth, long id) {
        long me = MyOrdersController.current(repository, auth).id();
        return repository.findOrder(id)
                .filter(o -> o.userId() == me)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
