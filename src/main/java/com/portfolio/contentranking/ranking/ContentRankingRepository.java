package com.portfolio.contentranking.ranking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis Sorted Set 기반 콘텐츠 랭킹 저장소.
 *
 * member = contentId(문자열), score = 좋아요 수
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ContentRankingRepository {

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

    /** 순위와 점수만 담은 중간 결과 */
    public record RankedContent(int rank, Long contentId, long score) {
    }
}