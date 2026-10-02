package com.portfolio.contentranking.like;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContentLikeRepository extends JpaRepository<ContentLike, Long> {

    boolean existsByContentIdAndMemberId(Long contentId, Long memberId);

    Optional<ContentLike> findByContentIdAndMemberId(Long contentId, Long memberId);

    long countByContentId(Long contentId);
}
