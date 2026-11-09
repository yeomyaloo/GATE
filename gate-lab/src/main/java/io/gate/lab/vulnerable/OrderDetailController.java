package io.gate.lab.vulnerable;

import io.gate.lab.common.LabRepository;
import io.gate.lab.common.MyOrdersController;
import io.gate.lab.common.MyOrdersController.OrderRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 주문 하나 조회·수정·삭제. */
@RestController
@Profile("vulnerable")
public class OrderDetailController {

    private final LabRepository repository;

    public OrderDetailController(LabRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/orders/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return LabRepository.view(load(id));
    }

    @PutMapping("/api/orders/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody OrderRequest req) {
        LabRepository.Order order = load(id);
        OrderRequest merged = new OrderRequest(order.productId(), req.quantity(), req.note(), req.shippingAddress());
        MyOrdersController.validate(repository, merged);
        repository.updateOrder(id, merged.quantity(), MyOrdersController.text(merged.note()),
                MyOrdersController.text(merged.shippingAddress()));
        return LabRepository.view(load(id));
    }

    @DeleteMapping("/api/orders/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        load(id);
        repository.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }

    private LabRepository.Order load(long id) {
        return repository.findOrder(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
