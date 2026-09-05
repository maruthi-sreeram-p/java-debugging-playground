package com.debuglab.tickets.controller;

import com.debuglab.tickets.dto.LoginRequest;
import com.debuglab.tickets.dto.RefreshRequest;
import com.debuglab.tickets.entity.AppUser;
import com.debuglab.tickets.repository.AppUserRepository;
import com.debuglab.tickets.security.JwtService;
import io.jsonwebtoken.Claims;
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

    public AuthController(AppUserRepository appUserRepository,
                          PasswordEncoder passwordEncoder,
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
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", 401);
            error.put("message", "Invalid username or password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }

        AppUser user = found.get();
        log.info("Login succeeded for {}", user.getUsername());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", jwtService.issue(user));
        body.put("username", user.getUsername());
        body.put("role", user.getRole());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@Valid @RequestBody RefreshRequest request) {
        Claims claims = jwtService.claimsForRefresh(request.getToken());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", jwtService.reissue(claims));
        body.put("subject", claims.getSubject());
        return ResponseEntity.ok(body);
    }
}
