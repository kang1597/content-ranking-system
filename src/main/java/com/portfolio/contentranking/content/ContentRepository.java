package com.portfolio.contentranking.content;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRepository extends JpaRepository<Content, Long> {

    Page<Content> findByMemberId(Long memberId, Pageable pageable);

    /** 목록 조회 시 작성자를 함께 가져와 추가 쿼리를 없앤다. */
    @Override
    @EntityGraph(attributePaths = "member")
    Page<Content> findAll(Pageable pageable);
}