package com.github.wuzhudexiaopacai.security;

import com.github.wuzhudexiaopacai.auth.entity.SysUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(@Value("${coherer.security.jwt-secret}") String secret,
                      @Value("${coherer.security.jwt-access-ttl-seconds:7200}") long accessTtlSeconds,
                      @Value("${coherer.security.jwt-refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("coherer.security.jwt-secret 至少需要 32 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public String issueAccess(SysUser user) {
        return issue(user, TYPE_ACCESS, accessTtlSeconds);
    }

    public String issueRefresh(SysUser user, String jti) {
        return issue(user, TYPE_REFRESH, refreshTtlSeconds, jti);
    }

    public Claims parseAccess(String token) {
        return requireType(parse(token), TYPE_ACCESS);
    }

    public Claims parseRefresh(String token) {
        return requireType(parse(token), TYPE_REFRESH);
    }

    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long refreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    private String issue(SysUser user, String type, long ttlSeconds) {
        return issue(user, type, ttlSeconds, null);
    }

    private String issue(SysUser user, String type, long ttlSeconds, String jti) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", String.valueOf(user.getId()))
                .claim("role", user.getRole())
                .claim("ver", user.getTokenVersion() == null ? 0 : user.getTokenVersion())
                .claim("type", type);
        if (jti != null) {
            builder.id(jti);
        }
        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Claims requireType(Claims claims, String expected) {
        String type = claims.get("type", String.class);
        if (!expected.equals(type)) {
            throw new IllegalArgumentException("令牌类型不正确");
        }
        return claims;
    }
}
