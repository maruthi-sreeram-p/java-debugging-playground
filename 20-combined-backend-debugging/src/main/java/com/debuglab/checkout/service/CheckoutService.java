package com.debuglab.checkout.service;

import com.debuglab.checkout.dto.CheckoutRequest;
import com.debuglab.checkout.dto.OrderResponse;
import com.debuglab.checkout.dto.ProductView;
import com.debuglab.checkout.entity.CustomerOrder;
import com.debuglab.checkout.entity.Product;
import com.debuglab.checkout.event.OrderPlacedEvent;
import com.debuglab.checkout.exception.CheckoutDeclinedException;
import com.debuglab.checkout.exception.InsufficientStockException;
import com.debuglab.checkout.exception.OrderNotFoundException;
import com.debuglab.checkout.exception.ProductNotFoundException;
import com.debuglab.checkout.repository.CustomerOrderRepository;
import com.debuglab.checkout.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CatalogueService catalogueService;
    private final ProductRepository productRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final PaymentGateway paymentGateway;
    private final RiskScreeningService riskScreeningService;

    public CheckoutService(CatalogueService catalogueService,
                           ProductRepository productRepository,
                           CustomerOrderRepository customerOrderRepository,
                           OrderEventPublisher orderEventPublisher,
                           PaymentGateway paymentGateway,
                           RiskScreeningService riskScreeningService) {
        this.catalogueService = catalogueService;
        this.productRepository = productRepository;
        this.customerOrderRepository = customerOrderRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.paymentGateway = paymentGateway;
        this.riskScreeningService = riskScreeningService;
    }

    @Transactional
    public OrderResponse checkout(String username, CheckoutRequest request)
            throws CheckoutDeclinedException {

        ProductView product = catalogueService.findBySku(request.getSku());
        if (product.getStock() < request.getQuantity()) {
            throw new InsufficientStockException(request.getSku(), request.getQuantity(),
                    product.getStock());
        }

        BigDecimal amount = product.getPrice().multiply(BigDecimal.valueOf(request.getQuantity()));
        String orderRef = nextOrderRef();

        CustomerOrder order = new CustomerOrder(orderRef, username, request.getSku(),
                request.getQuantity(), amount, "PLACED");
        customerOrderRepository.save(order);

        Product stored = productRepository.findBySku(request.getSku())
                .orElseThrow(() -> new ProductNotFoundException(request.getSku()));
        stored.setStock(stored.getStock() - request.getQuantity());
        productRepository.save(stored);

        orderEventPublisher.publish(new OrderPlacedEvent(orderRef, username, request.getSku(),
                request.getQuantity(), amount));

        paymentGateway.authorise(request.getCardToken(), amount);
        riskScreeningService.screen(orderRef, amount);

        log.info("Order {} placed by {} for {} x {} at {}", orderRef, username,
                request.getQuantity(), request.getSku(), amount);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse findByRef(String orderRef) {
        CustomerOrder order = customerOrderRepository.findByOrderRef(orderRef)
                .orElseThrow(() -> new OrderNotFoundException(orderRef));
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findMyOrders(String username) {
        List<OrderResponse> responses = new ArrayList<>();
        for (CustomerOrder order : customerOrderRepository
                .findByCustomerUsernameOrderByIdDesc(username)) {
            responses.add(toResponse(order));
        }
        return responses;
    }

    private OrderResponse toResponse(CustomerOrder order) {
        return new OrderResponse(order.getOrderRef(), order.getCustomerUsername(), order.getSku(),
                order.getQuantity(), order.getAmount(), order.getStatus(), order.getPlacedAt());
    }

    private String nextOrderRef() {
        return "ORD-" + LocalDate.now().format(REF_DATE) + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
