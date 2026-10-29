package com.portfolio.contentranking.like.dto;

public record LikeResponse(
        Long contentId,
        long likeCount,
        boolean liked
) {
}