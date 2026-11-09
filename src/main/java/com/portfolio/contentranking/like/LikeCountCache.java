package com.portfolio.contentranking.like;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;

/**
 * 좋아요 수 조회 캐시.
 *
 * Redis 장애가 서비스 장애로 이어지지 않도록, 모든 접근은 실패해도 흐름을 막지 않는다.
 * (캐시는 "있으면 빠르고 없으면 느린" 보조 수단이어야 한다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeCountCache {

    private static final String KEY_PREFIX = "content:like:count:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public Optional<Long> find(Long contentId) {
        try {
            String value = redisTemplate.opsForValue().get(key(contentId));
            return (value == null) ? Optional.empty() : Optional.of(Long.valueOf(value));
        } catch (Exception e) {
            log.warn("좋아요 수 캐시 조회 실패. contentId={}, reason={}", contentId, e.getMessage());
            return Optional.empty();   // 캐시 미스로 처리 → DB 조회로 이어짐
        }
    }

    public void put(Long contentId, long count) {
        try {
            redisTemplate.opsForValue().set(key(contentId), String.valueOf(count), TTL);
        } catch (Exception e) {
            log.warn("좋아요 수 캐시 저장 실패. contentId={}, reason={}", contentId, e.getMessage());
        }
    }

    public void evict(Long contentId) {
        try {
            redisTemplate.delete(key(contentId));
        } catch (Exception e) {
            log.warn("좋아요 수 캐시 삭제 실패. contentId={}, reason={}", contentId, e.getMessage());
        }
    }

    /**
     * 트랜잭션이 끝난 뒤에 캐시를 지운다.
     * 커밋 전에 지우면, 아직 반영되지 않은 값을 다른 요청이 읽어 캐시에 다시 넣을 수 있다.
     */
    public void evictAfterCompletion(Long contentId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(contentId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        evict(contentId);
                    }
                });
    }

    private String key(Long contentId) {
        return KEY_PREFIX + contentId;
    }

    /** Redis 에서 원자적으로 1 증가시키고 증가 후 값을 돌려준다. */
    public Optional<Long> increment(Long contentId) {
        try {
            Long value = redisTemplate.opsForValue().increment(key(contentId));
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.warn("좋아요 수 캐시 증가 실패. contentId={}, reason={}", contentId, e.getMessage());
            return Optional.empty();
        }
    }
}