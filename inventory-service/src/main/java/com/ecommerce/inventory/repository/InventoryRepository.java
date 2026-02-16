package com.ecommerce.inventory.repository;

import com.ecommerce.inventory.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InventoryRepository extends JpaRepository<Inventory, String> {
    List<Inventory> findAllByOrderByProductIdAsc();
}
