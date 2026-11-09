package com.portfolio.contentranking.ranking;

import com.portfolio.contentranking.ranking.dto.RankingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rankings")
@RequiredArgsConstructor
public class RankingController {

    private static final int MAX_LIMIT = 100;

    private final RankingService rankingService;

    @GetMapping("/contents")
    public ResponseEntity<RankingResponse> getContentRanking(
            @RequestParam(defaultValue = "DAILY") RankingPeriod period,
            @RequestParam(defaultValue = "10") int limit
    ) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return ResponseEntity.ok(rankingService.getRanking(period, safeLimit));
    }
}