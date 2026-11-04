package com.portfolio.contentranking.like.strategy;

import com.portfolio.contentranking.common.exception.AlreadyLikedException;
import com.portfolio.contentranking.common.exception.ContentNotFoundException;
import com.portfolio.contentranking.common.exception.MemberNotFoundException;
import com.portfolio.contentranking.content.Content;
import com.portfolio.contentranking.content.ContentRepository;
import com.portfolio.contentranking.like.ContentLike;
import com.portfolio.contentranking.like.ContentLikeRepository;
import com.portfolio.contentranking.member.Member;
import com.portfolio.contentranking.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ① 조회 후 UPDATE — 동시성을 고려하지 않은 기준점.
 *
 * INSERT(부모 행에 공유 락) → UPDATE(같은 행에 배타 락) 순서라
 * 데드락과 갱신 손실이 모두 발생한다.
 */
@Component
@RequiredArgsConstructor
public class NaiveLikeStrategy implements LikeStrategy {

    private final ContentLikeRepository contentLikeRepository;
    private final ContentRepository contentRepository;
    private final MemberRepository memberRepository;

    @Override
    public String name() {
        return "① 조회 후 UPDATE";
    }

    @Override
    @Transactional
    public void like(Long contentId, Long memberId) {
        if (contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId)) {
            throw new AlreadyLikedException();
        }

        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new ContentNotFoundException(contentId));
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));

        contentLikeRepository.save(
                ContentLike.builder().content(content).member(member).build()
        );

        content.increaseLikeCount();   // 더티 체킹 → 커밋 시점에 UPDATE
    }
}