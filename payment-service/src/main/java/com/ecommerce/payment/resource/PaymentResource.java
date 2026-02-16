package com.ecommerce.payment.resource;

import com.ecommerce.payment.model.Payment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResource {
    private String paymentId;
    private String orderId;
    private String customerId;
    private Double amount;
    private Payment.PaymentStatus status;
    private String paymentMethod;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
}
