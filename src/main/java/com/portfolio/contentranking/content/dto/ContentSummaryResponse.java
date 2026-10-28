package com.portfolio.contentranking.content.dto;

import com.portfolio.contentranking.content.Content;

import java.time.LocalDateTime;

public record ContentSummaryResponse(
        Long contentId,
        String title,
        String authorNickname,
        long likeCount,
        long viewCount,
        LocalDateTime createdAt
) {
    public static ContentSummaryResponse from(Content content) {
        return new ContentSummaryResponse(
                content.getId(),
                content.getTitle(),
                content.getMember().getNickname(),
                content.getLikeCount(),
                content.getViewCount(),
                content.getCreatedAt()
        );
    }
}