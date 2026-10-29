package com.portfolio.contentranking.common.exception;

public class AlreadyLikedException extends RuntimeException {

    public AlreadyLikedException() {
        super("이미 좋아요를 누른 콘텐츠입니다.");
    }
}