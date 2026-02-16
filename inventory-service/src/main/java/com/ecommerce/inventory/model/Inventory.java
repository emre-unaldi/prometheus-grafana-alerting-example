package com.ecommerce.inventory.model;

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
@Table(name = "inventory")
public class Inventory {
    @Id
    private String productId;

    private String productName;
    private Integer quantity;
    private Integer reservedQuantity;
    private Integer availableQuantity;
    private LocalDateTime lastUpdated;

    public Integer calculateAvailableQuantity() {
        return quantity - reservedQuantity;
    }

    public boolean isCriticalStock() {
        return calculateAvailableQuantity() < 10;
    }
}
