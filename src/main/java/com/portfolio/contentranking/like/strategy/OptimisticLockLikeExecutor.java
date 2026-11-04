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
 * 낙관적 락 전략의 "한 번의 시도"를 담당한다.
 * 재시도는 트랜잭션 밖에서 이루어져야 하므로 별도 Bean으로 분리했다.
 */
@Component
@RequiredArgsConstructor
public class OptimisticLockLikeExecutor {

    private final ContentLikeRepository contentLikeRepository;
    private final ContentRepository contentRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public void likeOnce(Long contentId, Long memberId) {
        if (contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId)) {
            throw new AlreadyLikedException();
        }

        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new ContentNotFoundException(contentId));

        content.increaseLikeCount();
        contentRepository.flush();     // 버전 충돌을 여기서 즉시 확인 + 데드락 회피

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));

        contentLikeRepository.save(
                ContentLike.builder().content(content).member(member).build()
        );
    }
}