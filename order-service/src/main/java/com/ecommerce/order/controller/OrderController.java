package com.ecommerce.order.controller;

import com.ecommerce.order.dto.OrderRequest;
import com.ecommerce.order.resource.OrderResource;
import com.ecommerce.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResource> createOrder(@Valid @RequestBody OrderRequest request) {
        log.info("Received create order request: {}", request);
        OrderResource response = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResource> getOrder(@PathVariable String orderId) {
        log.info("Received get order request: {}", orderId);
        OrderResource response = orderService.getOrder(orderId);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<OrderResource> cancelOrder(@PathVariable String orderId) {
        log.info("Received cancel order request: {}", orderId);
        OrderResource response = orderService.cancelOrder(orderId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Order Service is healthy!");
    }
}
