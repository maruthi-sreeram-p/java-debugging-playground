package com.debuglab.orders.service;

import com.debuglab.orders.dto.AddressRequest;
import com.debuglab.orders.dto.CreateOrderRequest;
import com.debuglab.orders.dto.OrderItemRequest;
import com.debuglab.orders.dto.OrderItemResponse;
import com.debuglab.orders.dto.OrderResponse;
import com.debuglab.orders.entity.Customer;
import com.debuglab.orders.entity.Order;
import com.debuglab.orders.entity.OrderItem;
import com.debuglab.orders.entity.ShippingAddress;
import com.debuglab.orders.exception.NotFoundException;
import com.debuglab.orders.repository.CustomerRepository;
import com.debuglab.orders.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;

    public OrderService(OrderRepository orderRepository, CustomerRepository customerRepository) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
    }

    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new NotFoundException("customer", request.getCustomerId()));

        Order order = new Order();
        order.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setCustomer(customer);
        order.setPlacedAt(LocalDateTime.now());
        order.setStatus("PLACED");
        order.setTotalAmount(sumOf(order.getItems()));

        if (request.getShippingAddress() != null) {
            order.setShippingAddress(toAddress(request.getShippingAddress()));
        }

        for (OrderItemRequest itemRequest : request.getItems()) {
            OrderItem item = new OrderItem();
            item.setProductName(itemRequest.getProductName());
            item.setUnitPrice(itemRequest.getUnitPrice());
            item.setQuantity(itemRequest.getQuantity());
            order.getItems().add(item);
        }

        Order saved = orderRepository.save(order);
        log.info("Placed order {} for customer {} with {} items",
                saved.getOrderNumber(), customer.getId(), saved.getItems().size());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("order", id));
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public Order findEntity(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("order", id));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> ordersForCustomer(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("customer", customerId));

        List<OrderResponse> responses = new ArrayList<>();
        for (Order order : customer.getOrders()) {
            responses.add(toResponse(order));
        }
        log.debug("Customer {} has {} orders", customerId, responses.size());
        return responses;
    }

    @Transactional
    public void removeItem(Long orderId, Long itemId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("order", orderId));

        OrderItem target = null;
        for (OrderItem item : order.getItems()) {
            if (item.getId().equals(itemId)) {
                target = item;
                break;
            }
        }
        if (target == null) {
            throw new NotFoundException("order item", itemId);
        }

        order.getItems().remove(target);
        order.setTotalAmount(sumOf(order.getItems()));
        orderRepository.save(order);
        log.info("Removed item {} from order {}", itemId, orderId);
    }

    private ShippingAddress toAddress(AddressRequest request) {
        ShippingAddress address = new ShippingAddress();
        address.setLine1(request.getLine1());
        address.setLine2(request.getLine2());
        address.setCity(request.getCity());
        address.setState(request.getState());
        address.setPincode(request.getPincode());
        return address;
    }

    private BigDecimal sumOf(List<OrderItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : items) {
            total = total.add(item.getLineTotal());
        }
        return total;
    }

    private OrderResponse toResponse(Order order) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setOrderNumber(order.getOrderNumber());
        response.setStatus(order.getStatus());
        response.setPlacedAt(order.getPlacedAt());
        response.setTotalAmount(order.getTotalAmount());

        if (order.getCustomer() != null) {
            response.setCustomerId(order.getCustomer().getId());
            response.setCustomerName(order.getCustomer().getName());
        }

        List<OrderItemResponse> items = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            OrderItemResponse itemResponse = new OrderItemResponse();
            itemResponse.setId(item.getId());
            itemResponse.setProductName(item.getProductName());
            itemResponse.setUnitPrice(item.getUnitPrice());
            itemResponse.setQuantity(item.getQuantity());
            itemResponse.setLineTotal(item.getLineTotal());
            items.add(itemResponse);
        }
        response.setItems(items);

        ShippingAddress address = order.getShippingAddress();
        if (address != null) {
            response.setShippingAddress(address.getLine1() + ", " + address.getCity()
                    + ", " + address.getState() + " " + address.getPincode());
        }

        return response;
    }
}
