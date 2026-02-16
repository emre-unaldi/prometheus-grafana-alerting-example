package com.ecommerce.inventory.controller;

import com.ecommerce.inventory.dto.InventoryUpdateDto;
import com.ecommerce.inventory.dto.StockChangeDto;
import com.ecommerce.inventory.model.Inventory;
import com.ecommerce.inventory.resource.InventoryResource;
import com.ecommerce.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.ConversionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final ConversionService conversionService;

    @GetMapping("/check/{productId}")
    public ResponseEntity<Boolean> checkInventory(@PathVariable String productId, @RequestParam Integer quantity) {
        log.info("Inventory check request - Product: {}, Quantity: {}", productId, quantity);
        boolean available = inventoryService.checkAvailability(productId, quantity);
        return ResponseEntity.ok(available);
    }

    @GetMapping("/{productId}")
    public ResponseEntity<InventoryResource> getInventory(@PathVariable String productId) {
        log.info("Get inventory request: {}", productId);
        Inventory inventory = inventoryService.getInventory(productId);
        return ResponseEntity.ok(conversionService.convert(inventory, InventoryResource.class));
    }

    @GetMapping
    public ResponseEntity<List<InventoryResource>> getAllInventory() {
        log.info("Get all inventory request");
        List<InventoryResource> inventories = inventoryService.getAllInventory().stream()
                .map(inv -> conversionService.convert(inv, InventoryResource.class))
                .toList();
        return ResponseEntity.ok(inventories);
    }

    @GetMapping("/critical")
    public ResponseEntity<List<InventoryResource>> getCriticalStock() {
        log.info("Get critical stock request");
        List<InventoryResource> criticalProducts = inventoryService.getCriticalStockProducts().stream()
                .map(inv -> conversionService.convert(inv, InventoryResource.class))
                .toList();
        return ResponseEntity.ok(criticalProducts);
    }

    @PutMapping("/{productId}")
    public ResponseEntity<InventoryResource> updateInventory(@PathVariable String productId, @RequestBody InventoryUpdateDto request) {
        log.info("Update inventory request - Product: {}, Quantity: {}", productId, request.getQuantity());
        Inventory updated = inventoryService.updateInventory(productId, request.getQuantity());
        return ResponseEntity.ok(conversionService.convert(updated, InventoryResource.class));
    }

    @PostMapping("/{productId}/decrease")
    public ResponseEntity<String> decreaseStock(@PathVariable String productId, @RequestBody StockChangeDto request) {
        log.info("Decrease stock request - Product: {}, Quantity: {}", productId, request.getQuantity());
        inventoryService.decreaseStock(productId, request.getQuantity());
        return ResponseEntity.ok("Stock decreased successfully");
    }

    @PostMapping("/{productId}/increase")
    public ResponseEntity<String> increaseStock(@PathVariable String productId, @RequestBody StockChangeDto request) {
        log.info("Increase stock request - Product: {}, Quantity: {}", productId, request.getQuantity());
        inventoryService.increaseStock(productId, request.getQuantity());
        return ResponseEntity.ok("Stock increased successfully");
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Inventory Service is healthy!");
    }
}
