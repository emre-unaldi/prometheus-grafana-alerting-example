package com.ecommerce.order.model;

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
@Table(name = "orders")
public class Order {
    @Id
    private String orderId;

    private String customerId;
    private String productId;
    private Integer quantity;
    private Double totalAmount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum OrderStatus {
        PENDING,
        INVENTORY_CHECKED,
        PAYMENT_PROCESSING,
        PAYMENT_COMPLETED,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
