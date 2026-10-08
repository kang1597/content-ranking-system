package com.portfolio.contentranking.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Slf4j
@Component
public class JwtProvider {

    private final SecretKey key;
    private final long expirationMillis;

    public JwtProvider(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.expiration-millis}") long expirationMillis
    ){
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMillis = expirationMillis;
    }

    /**
     * 회원 ID를 담은 액세스 토큰을 발급한다.
     */
    public String createToken(Long memberId){
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMillis);

        return Jwts.builder()
                .subject(String.valueOf(memberId))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** 서명과 만료를 검증한다. 유효하지 않으면 false. */
    public boolean validate(String token){
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("만료된 JWT입니다. exp={}", e.getClaims().getExpiration());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("유효하지 않은 JWT입니다. reason={}", e.getMessage());
        }
        return false;
    }

    /**
     * 토큰에서 회원 ID를 꺼낸다. 호출 전에 validate()로 검증되어 있어야 한다.
     */
    public Long getMemberId(String token){
        return Long.valueOf(parseClaims(token).getSubject());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
