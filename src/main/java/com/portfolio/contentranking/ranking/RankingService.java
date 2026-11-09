package com.portfolio.contentranking.ranking;

import com.portfolio.contentranking.content.Content;
import com.portfolio.contentranking.content.ContentRepository;
import com.portfolio.contentranking.ranking.dto.RankingItem;
import com.portfolio.contentranking.ranking.dto.RankingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    private final ContentRankingRepository contentRankingRepository;
    private final ContentRepository contentRepository;
    private final RankingRebuildService rankingRebuildService;

    @Transactional(readOnly = true)
    public RankingResponse getRanking(RankingPeriod period, int limit) {
        List<ContentRankingRepository.RankedContent> ranked =
                contentRankingRepository.findTop(period, limit);

        // 키 자체가 없으면 Redis 유실로 보고 DB에서 재구축한다
        if (ranked.isEmpty() && contentRankingRepository.needsRebuild(period)) {
            int rebuilt = rankingRebuildService.rebuild(period);
            log.info("랭킹 키 부재로 재구축 수행. period={}, 건수={}", period, rebuilt);
            ranked = contentRankingRepository.findTop(period, limit);
        }

        if (ranked.isEmpty()) {
            return new RankingResponse(period.name().toLowerCase(), List.of());
        }

        // 제목을 채우기 위해 필요한 콘텐츠만 한 번에 조회
        List<Long> contentIds = ranked.stream()
                .map(ContentRankingRepository.RankedContent::contentId)
                .toList();

        Map<Long, Content> contents = contentRepository.findAllById(contentIds).stream()
                .collect(Collectors.toMap(Content::getId, Function.identity()));

        List<RankingItem> items = ranked.stream()
                .filter(r -> contents.containsKey(r.contentId()))   // 삭제된 콘텐츠 제외
                .map(r -> new RankingItem(
                        r.rank(),
                        r.contentId(),
                        contents.get(r.contentId()).getTitle(),
                        r.score()))
                .toList();

        return new RankingResponse(period.name().toLowerCase(), items);
    }
}