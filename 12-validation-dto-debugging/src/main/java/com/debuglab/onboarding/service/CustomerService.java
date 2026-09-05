package com.debuglab.onboarding.service;

import com.debuglab.onboarding.dto.AddressRequest;
import com.debuglab.onboarding.dto.CustomerRequest;
import com.debuglab.onboarding.dto.CustomerResponse;
import com.debuglab.onboarding.entity.Customer;
import com.debuglab.onboarding.exception.CustomerNotFoundException;
import com.debuglab.onboarding.exception.DuplicateEmailException;
import com.debuglab.onboarding.mapper.CustomerMapper;
import com.debuglab.onboarding.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    public CustomerService(CustomerRepository customerRepository, CustomerMapper customerMapper) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
    }

    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        if (customerRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException(request.getEmail());
        }
        Customer saved = customerRepository.save(customerMapper.toEntity(request));
        log.info("Onboarded customer {} ({})", saved.getId(), saved.getEmail());
        return customerMapper.toResponse(saved);
    }

    @Transactional
    public CustomerResponse update(Long id, CustomerRequest request) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
        customerMapper.applyTo(request, customer);
        return customerMapper.toResponse(customerRepository.save(customer));
    }

    @Transactional
    public CustomerResponse updateAddress(Long id, AddressRequest request) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
        customerMapper.applyAddress(request, customer);
        return customerMapper.toResponse(customerRepository.save(customer));
    }

    @Transactional(readOnly = true)
    public CustomerResponse findById(Long id) {
        return customerMapper.toResponse(customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id)));
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> findAtLeastAged(int minAge) {
        List<CustomerResponse> responses = new ArrayList<>();
        for (Customer customer : customerRepository.findByAgeGreaterThanEqual(minAge)) {
            responses.add(customerMapper.toResponse(customer));
        }
        return responses;
    }
}
