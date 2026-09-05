package com.debuglab.auth.service;

import com.debuglab.auth.dto.RegisterRequest;
import com.debuglab.auth.dto.UserResponse;
import com.debuglab.auth.entity.AppUser;
import com.debuglab.auth.exception.DuplicateAccountException;
import com.debuglab.auth.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AppUserRepository appUserRepository;

    public AccountService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (appUserRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateAccountException("Username " + request.getUsername() + " is taken");
        }
        if (appUserRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateAccountException("Email " + request.getEmail() + " is already registered");
        }

        AppUser user = new AppUser();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(request.getPassword());
        user.setDisplayName(request.getDisplayName());
        user.setCreatedAt(LocalDateTime.now());

        AppUser saved = appUserRepository.save(user);
        log.info("Registered account {} ({})", saved.getUsername(), saved.getEmail());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Optional<UserResponse> findByUsername(String username) {
        return appUserRepository.findByUsername(username).map(this::toResponse);
    }

    private UserResponse toResponse(AppUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(),
                user.getDisplayName(), user.isEnabled(), user.getCreatedAt());
    }
}
