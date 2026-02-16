package com.ecommerce.payment.converter;

import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.resource.PaymentResource;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class PaymentToPaymentResourceConverter implements Converter<Payment, PaymentResource> {

    @Override
    public PaymentResource convert(Payment payment) {
        return PaymentResource.builder()
                .paymentId(payment.getPaymentId())
                .orderId(payment.getOrderId())
                .customerId(payment.getCustomerId())
                .amount(payment.getAmount())
                .status(payment.getStatus())
                .paymentMethod(payment.getPaymentMethod())
                .failureReason(payment.getFailureReason())
                .createdAt(payment.getCreatedAt())
                .processedAt(payment.getProcessedAt())
                .build();
    }
}
