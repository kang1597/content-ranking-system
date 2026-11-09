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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좋아요 등록/취소.
 *
 * like_count 는 DB 원자적 연산(UPDATE ... SET like_count = like_count + 1)으로 갱신한다.
 * 값을 읽지 않으므로 갱신 손실이 없고, UPDATE 를 먼저 실행해 데드락도 피한다.
 * 다섯 가지 방식의 측정 결과는 README 참고.
 */
@Service
@RequiredArgsConstructor
public class LikeService {

    private final ContentLikeRepository contentLikeRepository;
    private final ContentRepository contentRepository;
    private final MemberRepository memberRepository;
    private final LikeCountCache likeCountCache;

    @Transactional
    public LikeResponse like(Long contentId, Long memberId) {
        if (contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId)) {
            throw new AlreadyLikedException();
        }

        // 락을 잡기 전에 끝낼 수 있는 조회를 먼저 한다
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));

        // UPDATE 를 먼저 실행해 배타 락을 선점한다 (데드락 회피)
        if (contentRepository.increaseLikeCount(contentId) == 0) {
            throw new ContentNotFoundException(contentId);
        }

        try {
            contentLikeRepository.save(
                    ContentLike.builder()
                            .content(contentRepository.getReferenceById(contentId))
                            .member(member)
                            .build()
            );
            contentLikeRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // existsBy 검사를 통과했지만 동시 요청이 먼저 삽입한 경우 (TOCTOU)
            throw new AlreadyLikedException();
        }

        likeCountCache.evictAfterCompletion(contentId);
        return new LikeResponse(contentId, currentLikeCount(contentId), true);
    }

    @Transactional
    public LikeResponse unlike(Long contentId, Long memberId) {
        if (contentLikeRepository.deleteByContentIdAndMemberId(contentId, memberId) == 0) {
            throw new LikeNotFoundException();
        }

        if (contentRepository.decreaseLikeCount(contentId) == 0) {
            throw new ContentNotFoundException(contentId);
        }

        likeCountCache.evictAfterCompletion(contentId);
        return new LikeResponse(contentId, currentLikeCount(contentId), false);
    }

    @Transactional(readOnly = true)
    public LikeResponse getLikeCount(Long contentId, Long memberId) {
        long count = likeCountCache.find(contentId)
                .orElseGet(() -> {
                    long fromDb = contentRepository.findById(contentId)
                            .orElseThrow(() -> new ContentNotFoundException(contentId))
                            .getLikeCount();
                    likeCountCache.put(contentId, fromDb);
                    return fromDb;
                });

        boolean liked = memberId != null
                && contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId);

        return new LikeResponse(contentId, count, liked);
    }

    private long currentLikeCount(Long contentId) {
        return contentRepository.findById(contentId)
                .orElseThrow(() -> new ContentNotFoundException(contentId))
                .getLikeCount();
    }
}