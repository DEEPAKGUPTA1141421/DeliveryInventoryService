package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import org.springframework.stereotype.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.Configuration.KeyLoader;

import io.jsonwebtoken.*;
import org.springframework.beans.factory.annotation.Value;

import java.util.Date;
import java.util.UUID;
import java.security.PrivateKey;
import java.security.PublicKey;

@Service
public class JwtService {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final long jwtExpirationInMillis;

    public JwtService(@Value("${jwt.private.key.path}") String privateKeyPath,
            @Value("${jwt.public.key.path}") String publicKeyPath,
            @Value("${jwt.expiration.in.millis}") long jwtExpirationInMillis) throws Exception {
        this.privateKey = KeyLoader.loadPrivateKey(privateKeyPath);
        this.publicKey = KeyLoader.loadPublicKey(publicKeyPath);
        this.jwtExpirationInMillis = jwtExpirationInMillis;
    }

    // Generate token with phone as subject
    public String generateToken(String phone, String role, UUID id) {
        return Jwts.builder()
                .setSubject(phone)
                .claim("role", role)
                .claim("id", id.toString())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpirationInMillis))
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    // Parse token claims
    public Claims parseClaims(String token) throws JwtException {
        return Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    // Extract phone number (subject)
    public String extractPhone(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public UUID extractId(String token) {
        String idStr = parseClaims(token).get("id", String.class);
        return UUID.fromString(idStr);
    }

    // Check if token is expired
    public boolean isTokenExpired(String token) {
        return parseClaims(token).getExpiration().before(new Date());
    }

    // Validate token (only expiry)
    public boolean validateToken(String token) {
        try {
            return !isTokenExpired(token);
        } catch (JwtException ex) {
            return false;
        }
    }
}


// ih ngyutu futu gyugyyyyyygtuttu