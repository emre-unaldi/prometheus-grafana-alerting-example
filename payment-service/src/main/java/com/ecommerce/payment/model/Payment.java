package com.ecommerce.payment.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payments")
public class Payment {
    @Id
    private String paymentId;

    private String orderId;
    private String customerId;
    private Double amount;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private String paymentMethod;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;

    public enum PaymentStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        REFUNDED
    }
}
