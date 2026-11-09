package com.portfolio.contentranking.ranking;

import com.portfolio.contentranking.content.ContentRepository;
import com.portfolio.contentranking.like.ContentLikeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * DB(진실의 원천)에서 Redis 랭킹을 다시 만든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingRebuildService {

    private final ContentRankingRepository contentRankingRepository;
    private final ContentRepository contentRepository;
    private final ContentLikeRepository contentLikeRepository;

    @Transactional(readOnly = true)
    public int rebuild(RankingPeriod period) {
        List<RankingScore> scores = loadScores(period);
        contentRankingRepository.rebuild(period, scores);
        return scores.size();
    }

    @Transactional(readOnly = true)
    public void rebuildAll() {
        for (RankingPeriod period : RankingPeriod.values()) {
            rebuild(period);
        }
    }

    private List<RankingScore> loadScores(RankingPeriod period) {
        return switch (period) {
            case ALL -> contentRepository.findAllLikeScores();
            case DAILY -> {
                LocalDate today = LocalDate.now();
                yield contentLikeRepository.countLikesBetween(
                        today.atStartOfDay(),
                        today.plusDays(1).atStartOfDay());
            }
        };
    }
}