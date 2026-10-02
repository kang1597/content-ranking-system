package com.portfolio.contentranking.content;

import com.portfolio.contentranking.common.BaseTimeEntity;
import com.portfolio.contentranking.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "content")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Content extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(nullable = false, length = 65535)
    private String body;

    /** 비정규화 컬럼 — 동시성 제어의 핵심 대상 */
    @Column(nullable = false)
    private long likeCount;

    @Column(nullable = false)
    private long viewCount;

    @Builder
    private Content(Member member, String title, String body) {
        this.member = member;
        this.title = title;
        this.body = body;
        this.likeCount = 0L;
        this.viewCount = 0L;
    }

    public void increaseLikeCount() {
        this.likeCount++;
    }

    public void decreaseLikeCount() {
        if (this.likeCount > 0) {
            this.likeCount--;
        }
    }

    public void increaseViewCount() {
        this.viewCount++;
    }
}