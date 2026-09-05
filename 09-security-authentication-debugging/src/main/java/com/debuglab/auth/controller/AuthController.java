package com.debuglab.auth.controller;

import com.debuglab.auth.dto.LoginRequest;
import com.debuglab.auth.dto.RegisterRequest;
import com.debuglab.auth.dto.UserResponse;
import com.debuglab.auth.service.AccountService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AccountService accountService;
    private final AuthenticationManager authenticationManager;

    public AuthController(AccountService accountService, AuthenticationManager authenticationManager) {
        this.accountService = accountService;
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        log.info("Login succeeded for {}", authentication.getName());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", authentication.getName());
        body.put("authorities", authentication.getAuthorities().toString());
        body.put("message", "Login successful");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        Map<String, Object> body = new LinkedHashMap<>();
        String name = authentication == null ? null : authentication.getName();

        body.put("username", name);
        body.put("authenticated", authentication != null && authentication.isAuthenticated());
        body.put("authorities", authentication == null ? "[]" : authentication.getAuthorities().toString());

        if (name != null) {
            accountService.findByUsername(name).ifPresent(user -> {
                body.put("email", user.getEmail());
                body.put("displayName", user.getDisplayName());
            });
        }

        return ResponseEntity.ok(body);
    }
}
