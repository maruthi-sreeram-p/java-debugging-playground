package com.debuglab.tickets.security;

import com.debuglab.tickets.entity.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey signingKey;
    private final long tokenLifetime;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expiration-seconds}") long expirationSeconds) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenLifetime = expirationSeconds;
    }

    public String issue(AppUser user) {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("role", user.getRole())
                .claim("displayName", user.getDisplayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(tokenLifetime)))
                .signWith(signingKey)
                .compact();

        log.debug("Issued token for {} valid until {}", user.getUsername(),
                Date.from(now.plusMillis(tokenLifetime)));
        return token;
    }

    public String reissue(Claims previousClaims) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(previousClaims.getSubject())
                .claim("role", previousClaims.get("role"))
                .claim("displayName", previousClaims.get("displayName"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(tokenLifetime)))
                .signWith(signingKey)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Claims claimsForRefresh(String token) {
        try {
            return parse(token);
        } catch (ExpiredJwtException ex) {
            return ex.getClaims();
        }
    }
}
