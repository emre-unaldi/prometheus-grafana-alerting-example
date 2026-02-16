package com.ecommerce.payment.controller;

import com.ecommerce.payment.dto.PaymentRequest;
import com.ecommerce.payment.resource.PaymentResource;
import com.ecommerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/process")
    public ResponseEntity<Boolean> processPayment(@RequestBody PaymentRequest request) {
        log.info("Payment request received - Order: {}, Amount: {}", request.getOrderId(), request.getAmount());
        boolean success = paymentService.processPayment(request);
        return ResponseEntity.ok(success);
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResource> getPayment(@PathVariable String paymentId) {
        log.info("Get payment request: {}", paymentId);
        PaymentResource payment = paymentService.getPayment(paymentId);
        return ResponseEntity.ok(payment);
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentResource> getPaymentByOrderId(@PathVariable String orderId) {
        log.info("Get payment by order request: {}", orderId);
        PaymentResource payment = paymentService.getPaymentByOrderId(orderId);
        return ResponseEntity.ok(payment);
    }

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<PaymentResource> refundPayment(@PathVariable String paymentId) {
        log.info("Refund payment request: {}", paymentId);
        PaymentResource payment = paymentService.refundPayment(paymentId);
        return ResponseEntity.ok(payment);
    }

    @PutMapping("/config/failure-rate")
    public ResponseEntity<String> setFailureRate(@RequestBody Map<String, Double> request) {
        Double rate = request.get("rate");
        log.info("Setting failure rate to: {}%", rate * 100);
        paymentService.setFailureRate(rate);
        return ResponseEntity.ok("Failure rate updated to: " + (rate * 100) + "%");
    }

    @GetMapping("/config/failure-rate")
    public ResponseEntity<Map<String, Double>> getFailureRate() {
        double rate = paymentService.getFailureRate();
        return ResponseEntity.ok(Map.of("failureRate", rate));
    }

    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        log.info("Get payment statistics request");
        Map<String, Object> stats = paymentService.getStatistics();
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Payment Service is healthy!");
    }
}
