package com.portfolio.contentranking.content;

import com.portfolio.contentranking.common.dto.PageResponse;
import com.portfolio.contentranking.common.exception.ContentNotFoundException;
import com.portfolio.contentranking.common.exception.MemberNotFoundException;
import com.portfolio.contentranking.content.dto.ContentCreateRequest;
import com.portfolio.contentranking.content.dto.ContentResponse;
import com.portfolio.contentranking.content.dto.ContentSummaryResponse;
import com.portfolio.contentranking.member.Member;
import com.portfolio.contentranking.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContentService {

    private final ContentRepository contentRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public ContentResponse create(Long memberId, ContentCreateRequest request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException(memberId));

        Content content = Content.builder()
                .member(member)
                .title(request.title())
                .body(request.body())
                .build();

        contentRepository.save(content);

        return ContentResponse.from(content);
    }

    @Transactional(readOnly = true)
    public PageResponse<ContentSummaryResponse> getList(Pageable pageable) {
        return PageResponse.from(
                contentRepository.findAll(pageable)
                        .map(ContentSummaryResponse::from)
        );
    }

    @Transactional(readOnly = true)
    public ContentResponse get(Long contentId) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new ContentNotFoundException(contentId));

        return ContentResponse.from(content);
    }
}