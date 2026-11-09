package com.portfolio.contentranking.content;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ContentRepository extends JpaRepository<Content, Long> {

    Page<Content> findByMemberId(Long memberId, Pageable pageable);

    /** 목록 조회 시 작성자를 함께 가져와 추가 쿼리를 없앤다. */
    @Override
    @EntityGraph(attributePaths = "member")
    Page<Content> findAll(Pageable pageable);

    /** 비관적 쓰기 락(SELECT ... FOR UPDATE)으로 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Content c where c.id = :id")
    Optional<Content> findByIdForUpdate(@Param("id") Long id);

    /** like_count 를 DB에서 직접 1 증가시킨다. 읽지 않으므로 갱신 손실이 없다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Content c set c.likeCount = c.likeCount + 1 where c.id = :id")
    int increaseLikeCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Content c set c.likeCount = c.likeCount - 1 "
            + "where c.id = :id and c.likeCount > 0")
    int decreaseLikeCount(@Param("id") Long id);

    /** 조회수 증분을 DB 에 반영한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Content c set c.viewCount = c.viewCount + :delta where c.id = :id")
    int addViewCount(@Param("id") Long id, @Param("delta") long delta);
}