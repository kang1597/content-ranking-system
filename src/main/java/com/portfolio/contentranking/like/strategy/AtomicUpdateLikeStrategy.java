package com.portfolio.contentranking.like.strategy;

import com.portfolio.contentranking.common.exception.AlreadyLikedException;
import com.portfolio.contentranking.common.exception.ContentNotFoundException;
import com.portfolio.contentranking.content.ContentRepository;
import com.portfolio.contentranking.like.ContentLike;
import com.portfolio.contentranking.like.ContentLikeRepository;
import com.portfolio.contentranking.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ④ DB 원자적 연산 — UPDATE ... SET like_count = like_count + 1
 *
 * 값을 읽지 않고 DB에게 "1 더해라"라고 지시하므로 읽고-쓰는 틈이 없다.
 */
@Component
@RequiredArgsConstructor
public class AtomicUpdateLikeStrategy implements LikeStrategy {

    private final ContentLikeRepository contentLikeRepository;
    private final ContentRepository contentRepository;
    private final MemberRepository memberRepository;

    @Override
    public String name() {
        return "④ DB 원자적 연산";
    }

    @Override
    @Transactional
    public void like(Long contentId, Long memberId) {
        if (contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId)) {
            throw new AlreadyLikedException();
        }

        // UPDATE 를 먼저 실행해 배타 락을 선점한다 (데드락 회피)
        int updated = contentRepository.increaseLikeCount(contentId);
        if (updated == 0) {
            throw new ContentNotFoundException(contentId);
        }

        contentLikeRepository.save(
                ContentLike.builder()
                        .content(contentRepository.getReferenceById(contentId))
                        .member(memberRepository.getReferenceById(memberId))
                        .build()
        );
    }
}