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
 * ② 비관적 락 — SELECT ... FOR UPDATE 로 읽는 순간부터 배타 락을 건다.
 *
 * 잠금 읽기(locking read)는 스냅샷이 아니라 최신 커밋 값을 읽으므로
 * 갱신 손실이 발생하지 않는다. 대신 요청이 완전히 직렬화된다.
 */
@Component
@RequiredArgsConstructor
public class PessimisticLockLikeStrategy implements LikeStrategy {

    private final ContentLikeRepository contentLikeRepository;
    private final ContentRepository contentRepository;
    private final MemberRepository memberRepository;

    @Override
    public String name() {
        return "② 비관적 락";
    }

    @Override
    @Transactional
    public void like(Long contentId, Long memberId) {
        if (contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId)) {
            throw new AlreadyLikedException();
        }

        Content content = contentRepository.findByIdForUpdate(contentId)
                .orElseThrow(() -> new ContentNotFoundException(contentId));

        content.increaseLikeCount();

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));

        contentLikeRepository.save(
                ContentLike.builder().content(content).member(member).build()
        );
    }
}