package com.portfolio.contentranking.like;

import com.portfolio.contentranking.common.exception.AlreadyLikedException;
import com.portfolio.contentranking.common.exception.ContentNotFoundException;
import com.portfolio.contentranking.common.exception.LikeNotFoundException;
import com.portfolio.contentranking.common.exception.MemberNotFoundException;
import com.portfolio.contentranking.content.Content;
import com.portfolio.contentranking.content.ContentRepository;
import com.portfolio.contentranking.like.dto.LikeResponse;
import com.portfolio.contentranking.member.Member;
import com.portfolio.contentranking.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LikeService {

    private final ContentLikeRepository contentLikeRepository;
    private final ContentRepository contentRepository;
    private final MemberRepository memberRepository;

    /**
     * 좋아요 등록.
     *
     * ⚠️ 이 구현은 동시성을 고려하지 않은 버전이다.
     *    - like_count 를 "읽고 → 더하고 → 쓰는" 방식이라 Lost Update 가 발생한다.
     *    - existsBy 검사와 save 사이의 틈으로 중복 삽입이 가능하다(TOCTOU).
     *    5단계에서 테스트로 문제를 드러낸 뒤 6단계에서 해결한다.
     */
    @Transactional
    public LikeResponse like(Long contentId, Long memberId) {
        if (contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId)) {
            throw new AlreadyLikedException();
        }

        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new ContentNotFoundException(contentId));
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));

        contentLikeRepository.save(
                ContentLike.builder()
                        .content(content)
                        .member(member)
                        .build()
        );

        content.increaseLikeCount();   // ← 읽고 → 더하고 → (더티 체킹으로) 쓴다

        return new LikeResponse(contentId, content.getLikeCount(), true);
    }

    @Transactional
    public LikeResponse unlike(Long contentId, Long memberId) {
        ContentLike contentLike = contentLikeRepository
                .findByContentIdAndMemberId(contentId, memberId)
                .orElseThrow(LikeNotFoundException::new);

        contentLikeRepository.delete(contentLike);

        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new ContentNotFoundException(contentId));

        content.decreaseLikeCount();

        return new LikeResponse(contentId, content.getLikeCount(), false);
    }

    @Transactional(readOnly = true)
    public LikeResponse getLikeCount(Long contentId, Long memberId) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new ContentNotFoundException(contentId));

        boolean liked = memberId != null
                && contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId);

        return new LikeResponse(contentId, content.getLikeCount(), liked);
    }
}