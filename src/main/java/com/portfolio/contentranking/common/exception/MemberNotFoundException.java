package com.portfolio.contentranking.common.exception;

public class MemberNotFoundException extends RuntimeException {

    public MemberNotFoundException(Long memberId) {
        super("존재하지 않는 회원입니다. id=" + memberId);
    }
}