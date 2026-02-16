package com.ecommerce.payment.service;

import com.ecommerce.payment.dto.PaymentRequest;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.resource.PaymentResource;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ConversionService conversionService;

    @Getter
    @Value("${payment.failure-rate}")
    private double failureRate;

    @Value("${payment.processing-time-ms}")
    private long processingTime;

    private final Random random = new Random();

    private static final String[] FAILURE_REASONS = {
        "INSUFFICIENT_FUNDS",
        "CARD_DECLINED",
        "EXPIRED_CARD",
        "FRAUD_DETECTED",
        "NETWORK_ERROR",
        "TIMEOUT"
    };

    @Transactional
    public boolean processPayment(PaymentRequest request) {
        try {
            log.info("Processing payment - Order: {}, Amount: {}",
                    request.getOrderId(), request.getAmount());

            Payment payment = Payment.builder()
                    .paymentId(UUID.randomUUID().toString())
                    .orderId(request.getOrderId())
                    .customerId(request.getCustomerId())
                    .amount(request.getAmount())
                    .paymentMethod(request.getPaymentMethod() != null ? request.getPaymentMethod() : "CREDIT_CARD")
                    .status(Payment.PaymentStatus.PROCESSING)
                    .createdAt(LocalDateTime.now())
                    .build();

            simulateProcessingDelay();

            boolean shouldFail = random.nextDouble() < failureRate;

            if (shouldFail) {
                String failureReason = getRandomFailureReason();
                payment.setStatus(Payment.PaymentStatus.FAILED);
                payment.setFailureReason(failureReason);
                payment.setProcessedAt(LocalDateTime.now());
                paymentRepository.save(payment);

                log.error("Payment FAILED - Order: {}, Reason: {}", request.getOrderId(), failureReason);
                return false;
            } else {
                payment.setStatus(Payment.PaymentStatus.COMPLETED);
                payment.setProcessedAt(LocalDateTime.now());
                paymentRepository.save(payment);

                log.info("Payment SUCCESSFUL - Order: {}, PaymentId: {}", request.getOrderId(), payment.getPaymentId());
                return true;
            }

        } catch (Exception e) {
            log.error("Error processing payment", e);
            return false;
        }
    }

    @Transactional(readOnly = true)
    public PaymentResource getPayment(String paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + paymentId));
        return conversionService.convert(payment, PaymentResource.class);
    }

    @Transactional(readOnly = true)
    public PaymentResource getPaymentByOrderId(String orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new RuntimeException("Payment not found for order: " + orderId));
        return conversionService.convert(payment, PaymentResource.class);
    }

    @Transactional
    public PaymentResource refundPayment(String paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + paymentId));

        if (payment.getStatus() != Payment.PaymentStatus.COMPLETED) {
            throw new RuntimeException("Can only refund completed payments");
        }

        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        log.info("Payment refunded: {}", paymentId);
        return conversionService.convert(payment, PaymentResource.class);
    }

    public void setFailureRate(double rate) {
        if (rate < 0 || rate > 1) {
            throw new IllegalArgumentException("Failure rate must be between 0 and 1");
        }
        this.failureRate = rate;
        log.info("Payment failure rate changed to: {}%", rate * 100);
    }

    private void simulateProcessingDelay() {
        try {
            long delay = (long) (processingTime * (0.8 + random.nextDouble() * 0.4));
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Processing delay interrupted", e);
        }
    }

    private String getRandomFailureReason() {
        return FAILURE_REASONS[random.nextInt(FAILURE_REASONS.length)];
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStatistics() {
        long totalPayments = paymentRepository.count();
        long successfulPayments = paymentRepository.findAll().stream()
                .filter(p -> p.getStatus() == Payment.PaymentStatus.COMPLETED)
                .count();
        long failedPayments = paymentRepository.findAll().stream()
                .filter(p -> p.getStatus() == Payment.PaymentStatus.FAILED)
                .count();

        double actualFailureRate = totalPayments > 0
                ? (double) failedPayments / totalPayments
                : 0.0;

        return Map.of(
            "totalPayments", totalPayments,
            "successfulPayments", successfulPayments,
            "failedPayments", failedPayments,
            "actualFailureRate", actualFailureRate,
            "configuredFailureRate", failureRate
        );
    }
}
