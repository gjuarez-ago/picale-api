package com.metricol.api.security;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Map;
import java.util.function.Function;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-days:15}")
    private long expirationDays;

    @Value("${jwt.issuer}")
    private String issuer;

    @Value("${jwt.audience}")
    private String audience;

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return generateToken(extraClaims, userDetails, Duration.ofDays(expirationDays));
    }

    /** Token con vida acotada para delegaciones internas, sin cambiar la sesion normal. */
    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails, Duration lifetime) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + lifetime.toMillis());

        return Jwts.builder()
                .setClaims(extraClaims)
                .setSubject(userDetails.getUsername())
                .setIssuer(issuer)
                .setAudience(audience)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    /** Comprueba los limites firmados de una identidad del asistente integrado. */
    public boolean isAssistantDelegation(String token, String workspaceId, String threadId, String runId) {
        Claims claims = extractClaim(token, Function.identity());
        return Boolean.TRUE.equals(claims.get("assistant", Boolean.class))
                && workspaceId.equals(claims.get("workspaceId", String.class))
                && threadId.equals(claims.get("assistantThreadId", String.class))
                && runId.equals(claims.get("assistantRunId", String.class));
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
        return resolver.apply(claims);
    }

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
