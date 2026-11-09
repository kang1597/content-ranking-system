package com.portfolio.contentranking.content;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 조회수 증분(delta) 보관소.
 *
 * DB 의 view_count 는 "마지막 동기화 시점까지의 누적"이고,
 * Redis 는 "그 이후 쌓인 증분"을 들고 있다. 실제 조회수는 둘의 합.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewCountCache {

    private static final String KEY_PREFIX = "content:view:delta:";

    private final StringRedisTemplate redisTemplate;

    /** 조회수를 1 올리고 증분을 돌려준다. */
    public long increment(Long contentId) {
        try {
            Long value = redisTemplate.opsForValue().increment(key(contentId));
            return (value == null) ? 0L : value;
        } catch (Exception e) {
            log.warn("조회수 증가 실패. contentId={}, reason={}", contentId, e.getMessage());
            return 0L;
        }
    }

    /** 아직 DB 에 반영되지 않은 증분. */
    public long getDelta(Long contentId) {
        try {
            String value = redisTemplate.opsForValue().get(key(contentId));
            return (value == null) ? 0L : Long.parseLong(value);
        } catch (Exception e) {
            log.warn("조회수 증분 조회 실패. contentId={}, reason={}", contentId, e.getMessage());
            return 0L;
        }
    }

    /**
     * 쌓인 증분을 모두 꺼내면서 동시에 지운다.
     * 꺼내기와 지우기가 원자적이어야 그 사이에 들어온 조회가 유실되지 않는다.
     */
    public Map<Long, Long> popAll() {
        Map<Long, Long> deltas = new HashMap<>();

        ScanOptions options = ScanOptions.scanOptions()
                .match(KEY_PREFIX + "*")
                .count(100)
                .build();

        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String value = redisTemplate.opsForValue().getAndDelete(key);
                if (value == null) {
                    continue;
                }
                Long contentId = Long.valueOf(key.substring(KEY_PREFIX.length()));
                deltas.put(contentId, Long.valueOf(value));
            }
        } catch (Exception e) {
            log.warn("조회수 증분 수집 실패. reason={}", e.getMessage());
        }

        return deltas;
    }

    private String key(Long contentId) {
        return KEY_PREFIX + contentId;
    }
}