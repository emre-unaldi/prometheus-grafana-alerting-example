package com.ecommerce.order.converter;

import com.ecommerce.order.model.Order;
import com.ecommerce.order.resource.OrderResource;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class OrderToOrderResourceConverter implements Converter<Order, OrderResource> {

    @Override
    public OrderResource convert(Order order) {
        return OrderResource.builder()
                .orderId(order.getOrderId())
                .customerId(order.getCustomerId())
                .productId(order.getProductId())
                .quantity(order.getQuantity())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .build();
    }
}
