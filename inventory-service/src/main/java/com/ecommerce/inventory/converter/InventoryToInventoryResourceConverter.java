package com.ecommerce.inventory.converter;

import com.ecommerce.inventory.model.Inventory;
import com.ecommerce.inventory.resource.InventoryResource;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class InventoryToInventoryResourceConverter implements Converter<Inventory, InventoryResource> {

    @Override
    public InventoryResource convert(Inventory inventory) {
        return InventoryResource.builder()
                .productId(inventory.getProductId())
                .productName(inventory.getProductName())
                .quantity(inventory.getQuantity())
                .reservedQuantity(inventory.getReservedQuantity())
                .availableQuantity(inventory.getAvailableQuantity())
                .lastUpdated(inventory.getLastUpdated())
                .build();
    }
}
