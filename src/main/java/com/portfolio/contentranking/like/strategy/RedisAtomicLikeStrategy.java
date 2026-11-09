package com.portfolio.contentranking.like.strategy;

import com.portfolio.contentranking.common.exception.AlreadyLikedException;
import com.portfolio.contentranking.common.exception.MemberNotFoundException;
import com.portfolio.contentranking.content.ContentRepository;
import com.portfolio.contentranking.like.ContentLike;
import com.portfolio.contentranking.like.ContentLikeRepository;
import com.portfolio.contentranking.like.LikeCountCache;
import com.portfolio.contentranking.member.Member;
import com.portfolio.contentranking.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ⑤ Redis 원자적 연산 — 집계를 Redis 가 맡고, DB 의 like_count 는 갱신하지 않는다.
 *
 * DB 행 잠금이 전혀 없어 가장 빠르지만,
 * content.like_count 와 Redis 값이 일시적으로 어긋난다(최종적 일관성).
 * 실제 운영에서는 주기적 배치로 DB 에 반영해야 한다.
 */
@Component
@RequiredArgsConstructor
public class RedisAtomicLikeStrategy implements LikeStrategy {

    private final ContentLikeRepository contentLikeRepository;
    private final ContentRepository contentRepository;
    private final MemberRepository memberRepository;
    private final LikeCountCache likeCountCache;

    @Override
    public String name() {
        return "⑤ Redis 원자적 연산";
    }

    @Override
    @Transactional
    public void like(Long contentId, Long memberId) {
        if (contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId)) {
            throw new AlreadyLikedException();
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));

        contentLikeRepository.save(
                ContentLike.builder()
                        .content(contentRepository.getReferenceById(contentId))
                        .member(member)
                        .build()
        );

        likeCountCache.increment(contentId);
    }
}