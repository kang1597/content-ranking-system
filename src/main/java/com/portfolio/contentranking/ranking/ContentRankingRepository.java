package com.portfolio.contentranking.ranking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Redis Sorted Set 기반 콘텐츠 랭킹 저장소.
 *
 * member = contentId(문자열), score = 좋아요 수
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ContentRankingRepository {

    /** 집계 결과가 0건일 때 재집계를 억제하는 기간 */
    private static final Duration REBUILD_COOLDOWN = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    /** 모든 기간의 랭킹에 점수를 반영한다. */
    public void addScore(Long contentId, double delta) {
        LocalDate today = LocalDate.now();

        for (RankingPeriod period : RankingPeriod.values()) {
            String key = period.key(today);
            try {
                redisTemplate.opsForZSet().incrementScore(key, String.valueOf(contentId), delta);
                if (period.ttl() != null) {
                    redisTemplate.expire(key, period.ttl());
                }
            } catch (Exception e) {
                log.warn("랭킹 점수 반영 실패. key={}, contentId={}, reason={}",
                        key, contentId, e.getMessage());
            }
        }
    }

    /** 상위 N개를 점수 내림차순으로 반환한다. */
    public List<RankedContent> findTop(RankingPeriod period, int limit) {
        String key = period.key(LocalDate.now());

        try {
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L);

            if (tuples == null || tuples.isEmpty()) {
                return List.of();
            }

            List<RankedContent> result = new ArrayList<>();
            int rank = 1;
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                if (tuple.getValue() == null || tuple.getScore() == null) {
                    continue;
                }
                result.add(new RankedContent(
                        rank++,
                        Long.valueOf(tuple.getValue()),
                        tuple.getScore().longValue()
                ));
            }
            return result;

        } catch (Exception e) {
            log.warn("랭킹 조회 실패. key={}, reason={}", key, e.getMessage());
            return List.of();
        }
    }

    /**
     * 재구축이 필요한지 판단한다.
     *
     * - 랭킹 키가 살아 있으면 불필요하다.
     * - 키는 없지만 최근에 재구축을 시도했다면, 그때 결과가 0건이었다는 뜻이므로 역시 불필요하다.
     *   (이 조건이 없으면 '오늘 좋아요 0건' 상태에서 조회마다 DB 집계가 반복된다)
     */
    public boolean needsRebuild(RankingPeriod period) {
        String key = period.key(LocalDate.now());
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                return false;
            }
            return !Boolean.TRUE.equals(redisTemplate.hasKey(rebuiltMarkerKey(key)));
        } catch (Exception e) {
            log.warn("재구축 필요 여부 확인 실패. reason={}", e.getMessage());
            return false;   // 알 수 없으면 건드리지 않는다 (보수적)
        }
    }

    /** DB에서 집계한 점수로 랭킹을 통째로 교체한다. */
    public void rebuild(RankingPeriod period, List<RankingScore> scores) {
        String key = period.key(LocalDate.now());

        // 결과가 0건이어도 "시도했다"는 사실을 남겨 반복 집계를 막는다
        markRebuilt(key);

        if (scores.isEmpty()) {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("랭킹 삭제 실패. key={}", key);
            }
            log.info("랭킹 재구축 결과 0건. key={}", key);
            return;
        }

        String tempKey = key + ":rebuilding:" + UUID.randomUUID();

        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = scores.stream()
                    .map(s -> ZSetOperations.TypedTuple.of(
                            String.valueOf(s.contentId()),
                            s.score().doubleValue()))
                    .collect(Collectors.toSet());

            redisTemplate.opsForZSet().add(tempKey, tuples);

            if (period.ttl() != null) {
                redisTemplate.expire(tempKey, period.ttl());
            }

            // 임시 키를 실제 키 위에 덮어쓴다 (원자적)
            redisTemplate.rename(tempKey, key);

            log.info("랭킹 재구축 완료. key={}, 건수={}", key, scores.size());

        } catch (Exception e) {
            log.error("랭킹 재구축 실패. key={}", key, e);
            try {
                redisTemplate.delete(tempKey);   // 찌꺼기 정리
            } catch (Exception ignored) {
            }
        }
    }

    private void markRebuilt(String key) {
        try {
            redisTemplate.opsForValue().set(rebuiltMarkerKey(key), "1", REBUILD_COOLDOWN);
        } catch (Exception e) {
            log.warn("재구축 표식 기록 실패. key={}", key);
        }
    }

    private String rebuiltMarkerKey(String key) {
        return key + ":rebuilt";
    }

    /** 순위와 점수만 담은 중간 결과 */
    public record RankedContent(int rank, Long contentId, long score) {
    }
}