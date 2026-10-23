package com.portfolio.contentranking.auth.dto;

import com.portfolio.contentranking.member.Member;

public record SignupResponse(
        Long memberId,
        String email,
        String nickname
) {
    public static SignupResponse from(Member member) {
        return new SignupResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname()
        );
    }
}