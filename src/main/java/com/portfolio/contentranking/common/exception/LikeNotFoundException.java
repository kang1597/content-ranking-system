package com.portfolio.contentranking.common.exception;

public class LikeNotFoundException extends RuntimeException {

    public LikeNotFoundException() {
        super("좋아요를 누르지 않은 콘텐츠입니다.");
    }
}