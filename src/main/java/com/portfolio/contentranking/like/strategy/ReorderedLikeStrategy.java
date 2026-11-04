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
 * ①′ 순서만 변경 — UPDATE를 먼저 실행해 배타 락을 선점한다.
 *
 * 데드락은 사라지지만, 읽은 값을 기준으로 계산하는 구조는 그대로라
 * 갱신 손실은 여전히 발생한다. "순서 변경만으로는 부족하다"를 보이기 위한 구현.
 */
@Component
@RequiredArgsConstructor
public class ReorderedLikeStrategy implements LikeStrategy {

    private final ContentLikeRepository contentLikeRepository;
    private final ContentRepository contentRepository;
    private final MemberRepository memberRepository;

    @Override
    public String name() {
        return "①′ 순서 변경";
    }

    @Override
    @Transactional
    public void like(Long contentId, Long memberId) {
        if (contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId)) {
            throw new AlreadyLikedException();
        }

        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new ContentNotFoundException(contentId));

        content.increaseLikeCount();
        contentRepository.flush();     // ← 여기서 UPDATE 실행 = 배타 락 선점

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));

        contentLikeRepository.save(
                ContentLike.builder().content(content).member(member).build()
        );
    }
}