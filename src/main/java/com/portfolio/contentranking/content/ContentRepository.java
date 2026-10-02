package com.portfolio.contentranking.content;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRepository extends JpaRepository<Content, Long> {

    Page<Content> findByMemberId(Long memberId, Pageable pageable);
}