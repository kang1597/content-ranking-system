package com.portfolio.contentranking.ranking.dto;

import java.util.List;

public record RankingResponse(
        String period,
        List<RankingItem> rankings
) {
}