package com.portfolio.contentranking.common.exception;

public class ContentNotFoundException extends RuntimeException {

    public ContentNotFoundException(Long contentId) {
        super("존재하지 않는 콘텐츠입니다. id=" + contentId);
    }
}