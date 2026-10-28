package com.portfolio.contentranking.content.dto;

import com.portfolio.contentranking.content.Content;

import java.time.LocalDateTime;

public record ContentResponse(
        Long contentId,
        String title,
        String body,
        Long authorId,
        String authorNickname,
        long likeCount,
        long viewCount,
        LocalDateTime createdAt
) {
    public static ContentResponse from(Content content) {
        return new ContentResponse(
                content.getId(),
                content.getTitle(),
                content.getBody(),
                content.getMember().getId(),
                content.getMember().getNickname(),
                content.getLikeCount(),
                content.getViewCount(),
                content.getCreatedAt()
        );
    }
}