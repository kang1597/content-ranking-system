package com.portfolio.contentranking.like.strategy;

public interface LikeStrategy {

    /** 비교표에 표시할 이름 */
    String name();

    /** 좋아요를 등록한다. */
    void like(Long contentId, Long memberId);
}