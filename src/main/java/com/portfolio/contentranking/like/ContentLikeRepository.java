package com.portfolio.contentranking.like;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ContentLikeRepository extends JpaRepository<ContentLike, Long> {

    boolean existsByContentIdAndMemberId(Long contentId, Long memberId);

    Optional<ContentLike> findByContentIdAndMemberId(Long contentId, Long memberId);

    long countByContentId(Long contentId);

    /** 조회 없이 한 번의 DELETE 로 지우고, 삭제된 행 수를 돌려준다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ContentLike cl "
            + "where cl.content.id = :contentId and cl.member.id = :memberId")
    int deleteByContentIdAndMemberId(@Param("contentId") Long contentId,
                                     @Param("memberId") Long memberId);
}
