package com.debuglab.checkout.consumer;

import com.debuglab.checkout.entity.CustomerOrder;
import com.debuglab.checkout.entity.Fulfilment;
import com.debuglab.checkout.entity.Product;
import com.debuglab.checkout.event.OrderPlacedEvent;
import com.debuglab.checkout.repository.CustomerOrderRepository;
import com.debuglab.checkout.repository.FulfilmentRepository;
import com.debuglab.checkout.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class InventoryConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryConsumer.class);

    private final FulfilmentRepository fulfilmentRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final ProductRepository productRepository;

    public InventoryConsumer(FulfilmentRepository fulfilmentRepository,
                             CustomerOrderRepository customerOrderRepository,
                             ProductRepository productRepository) {
        this.fulfilmentRepository = fulfilmentRepository;
        this.customerOrderRepository = customerOrderRepository;
        this.productRepository = productRepository;
    }

    @KafkaListener(topics = "${checkout.topic.orders}", groupId = "inventory-service")
    @Transactional
    public void onOrderPlaced(OrderPlacedEvent event) {
        Integer stockAfter = productRepository.findBySku(event.getSku())
                .map(Product::getStock)
                .orElse(null);

        fulfilmentRepository.save(new Fulfilment(event.getOrderRef(), "RESERVED", stockAfter));

        Optional<CustomerOrder> order = customerOrderRepository.findByOrderRef(event.getOrderRef());
        if (order.isPresent()) {
            order.get().setStatus("FULFILLED");
            customerOrderRepository.save(order.get());
        }

        log.info("FULFILMENT recorded for {} (stock now {})", event.getOrderRef(), stockAfter);
    }
}
