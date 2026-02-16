package com.ecommerce.order.service;

import com.ecommerce.order.dto.OrderRequest;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.resource.OrderResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final WebClient.Builder webClientBuilder;
    private final OrderRepository orderRepository;
    private final ConversionService conversionService;

    @Value("${external-services.inventory-service}")
    private String inventoryServiceUrl;

    @Value("${external-services.payment-service}")
    private String paymentServiceUrl;

    @Transactional
    public OrderResource createOrder(OrderRequest request) {
        try {
            log.info("Creating order for customer: {}, product: {}",
                    request.getCustomerId(), request.getProductId());

            Order order = Order.builder()
                    .orderId(UUID.randomUUID().toString())
                    .customerId(request.getCustomerId())
                    .productId(request.getProductId())
                    .quantity(request.getQuantity())
                    .totalAmount(request.getTotalAmount())
                    .status(Order.OrderStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            boolean inventoryAvailable = checkInventory(order);
            if (!inventoryAvailable) {
                log.warn("Insufficient inventory for order: {}", order.getOrderId());
                order.setStatus(Order.OrderStatus.FAILED);
                orderRepository.save(order);
                return conversionService.convert(order, OrderResource.class);
            }

            order.setStatus(Order.OrderStatus.INVENTORY_CHECKED);
            order.setUpdatedAt(LocalDateTime.now());

            order.setStatus(Order.OrderStatus.PAYMENT_PROCESSING);
            boolean paymentSuccess = processPayment(order);

            if (!paymentSuccess) {
                log.error("Payment failed for order: {}", order.getOrderId());
                order.setStatus(Order.OrderStatus.FAILED);
                orderRepository.save(order);
                return conversionService.convert(order, OrderResource.class);
            }

            order.setStatus(Order.OrderStatus.COMPLETED);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepository.save(order);

            log.info("Order completed successfully: {}", order.getOrderId());
            return conversionService.convert(order, OrderResource.class);

        } catch (Exception e) {
            log.error("Error creating order", e);
            throw new RuntimeException("Failed to create order: " + e.getMessage());
        }
    }

    private boolean checkInventory(Order order) {
        try {
            Boolean result = webClientBuilder.build()
                    .get()
                    .uri(inventoryServiceUrl + "/api/inventory/check/"
                            + order.getProductId() + "?quantity=" + order.getQuantity())
                    .retrieve()
                    .bodyToMono(Boolean.class)
                    .timeout(Duration.ofSeconds(5))
                    .onErrorResume(e -> {
                        log.error("Inventory service call failed", e);
                        return Mono.just(false);
                    })
                    .block();

            return result != null && result;

        } catch (Exception e) {
            log.error("Error checking inventory", e);
            return false;
        }
    }

    private boolean processPayment(Order order) {
        try {
            Map<String, Object> paymentRequest = Map.of(
                    "orderId", order.getOrderId(),
                    "amount", order.getTotalAmount(),
                    "customerId", order.getCustomerId()
            );

            Boolean result = webClientBuilder.build()
                    .post()
                    .uri(paymentServiceUrl + "/api/payment/process")
                    .bodyValue(paymentRequest)
                    .retrieve()
                    .bodyToMono(Boolean.class)
                    .timeout(Duration.ofSeconds(10))
                    .onErrorResume(e -> {
                        log.error("Payment service call failed", e);
                        return Mono.just(false);
                    })
                    .block();

            return result != null && result;

        } catch (Exception e) {
            log.error("Error processing payment", e);
            return false;
        }
    }

    @Transactional
    public OrderResource cancelOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if (order.getStatus() == Order.OrderStatus.COMPLETED) {
            throw new RuntimeException("Cannot cancel completed order");
        }

        order.setStatus(Order.OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        log.info("Order cancelled: {}", orderId);
        return conversionService.convert(order, OrderResource.class);
    }

    @Transactional(readOnly = true)
    public OrderResource getOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        return conversionService.convert(order, OrderResource.class);
    }
}
