package com.portfolio.contentranking.ranking.dto;

public record RankingItem(
        int rank,
        Long contentId,
        String title,
        long likeCount
) {
}