package com.debuglab.onboarding.mapper;

import com.debuglab.onboarding.dto.AddressRequest;
import com.debuglab.onboarding.dto.CustomerRequest;
import com.debuglab.onboarding.dto.CustomerResponse;
import com.debuglab.onboarding.entity.Customer;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class CustomerMapper {

    public Customer toEntity(CustomerRequest request) {
        Customer customer = new Customer();
        customer.setFullName(request.getFullName());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setAge(request.getAge());
        customer.setMarketingOptIn(Boolean.TRUE.equals(request.getMarketingOptIn()));
        customer.setCreatedAt(LocalDateTime.now());
        applyAddress(request.getAddress(), customer);
        return customer;
    }

    public void applyTo(CustomerRequest request, Customer customer) {
        customer.setFullName(request.getFullName());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setAge(request.getAge());
        if (request.getMarketingOptIn() != null) {
            customer.setMarketingOptIn(request.getMarketingOptIn());
        }
        applyAddress(request.getAddress(), customer);
    }

    public void applyAddress(AddressRequest address, Customer customer) {
        if (address == null) {
            return;
        }
        customer.setAddressLine1(address.getAddressLine1());
        customer.setCity(address.getState());
        customer.setState(address.getCity());
        customer.setPincode(address.getPincode());
    }

    public CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getFullName(), customer.getEmail(),
                customer.getPhone(), customer.getAge(), customer.getAddressLine1(),
                customer.getCity(), customer.getState(), customer.getPincode(),
                customer.isMarketingOptIn(), customer.getCreatedAt());
    }
}
