package com.debuglab.checkout.controller;

import com.debuglab.checkout.dto.LoginRequest;
import com.debuglab.checkout.entity.AppUser;
import com.debuglab.checkout.repository.AppUserRepository;
import com.debuglab.checkout.security.JwtService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
                          JwtService jwtService) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        Optional<AppUser> found = appUserRepository.findByUsername(request.getUsername());
        if (found.isEmpty()
                || !passwordEncoder.matches(request.getPassword(), found.get().getPasswordHash())) {
            log.info("Failed sign-in for {}", request.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        AppUser user = found.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", jwtService.issueToken(user.getUsername(), user.getRole()));
        body.put("username", user.getUsername());
        body.put("displayName", user.getDisplayName());
        body.put("role", user.getRole());
        body.put("expiresInSeconds", jwtService.getExpiresInSeconds());

        log.info("{} signed in", user.getUsername());
        return ResponseEntity.ok(body);
    }
}
