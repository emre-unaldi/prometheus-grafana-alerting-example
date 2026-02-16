package com.ecommerce.inventory.service;

import com.ecommerce.inventory.model.Inventory;
import com.ecommerce.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    @Transactional(readOnly = true)
    public boolean checkAvailability(String productId, Integer requiredQuantity) {
        log.info("Checking inventory for product: {}, required: {}", productId, requiredQuantity);

        Inventory inventory = inventoryRepository.findById(productId).orElse(null);

        if (inventory == null) {
            log.warn("Product not found in inventory: {}", productId);
            return false;
        }

        int available = inventory.calculateAvailableQuantity();

        if (inventory.isCriticalStock()) {
            log.warn("CRITICAL STOCK LEVEL for product: {}, available: {}", productId, available);
        }

        boolean hasStock = available >= requiredQuantity;

        if (hasStock) {
            log.info("Sufficient stock available for product: {}", productId);
        } else {
            log.warn("Insufficient stock for product: {}, available: {}, required: {}", productId, available, requiredQuantity);
        }

        return hasStock;
    }

    @Transactional
    public Inventory updateInventory(String productId, Integer quantity) {
        Inventory inventory = inventoryRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        inventory.setQuantity(quantity);
        inventory.setAvailableQuantity(inventory.calculateAvailableQuantity());
        inventory.setLastUpdated(LocalDateTime.now());
        inventoryRepository.save(inventory);

        if (inventory.isCriticalStock()) {
            log.warn("CRITICAL STOCK LEVEL for product: {}, quantity: {}", productId, quantity);
        }

        log.info("Inventory updated for product: {}, new quantity: {}", productId, quantity);
        return inventory;
    }

    @Transactional(readOnly = true)
    public Inventory getInventory(String productId) {
        return inventoryRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));
    }

    @Transactional(readOnly = true)
    public List<Inventory> getAllInventory() {
        return inventoryRepository.findAllByOrderByProductIdAsc();
    }

    @Transactional(readOnly = true)
    public List<Inventory> getCriticalStockProducts() {
        return inventoryRepository.findAll().stream()
                .filter(Inventory::isCriticalStock)
                .toList();
    }

    @Transactional
    public void decreaseStock(String productId, Integer quantity) {
        Inventory inventory = inventoryRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        int newQuantity = inventory.getQuantity() - quantity;

        if (newQuantity < 0) {
            throw new RuntimeException("Cannot decrease stock below zero");
        }

        updateInventory(productId, newQuantity);
    }

    @Transactional
    public void increaseStock(String productId, Integer quantity) {
        Inventory inventory = inventoryRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));

        int newQuantity = inventory.getQuantity() + quantity;
        updateInventory(productId, newQuantity);
    }
}
