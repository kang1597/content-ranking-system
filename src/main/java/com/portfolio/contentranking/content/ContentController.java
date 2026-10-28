package com.portfolio.contentranking.content;

import com.portfolio.contentranking.common.dto.PageResponse;
import com.portfolio.contentranking.content.dto.ContentCreateRequest;
import com.portfolio.contentranking.content.dto.ContentResponse;
import com.portfolio.contentranking.content.dto.ContentSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contents")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;

    @PostMapping
    public ResponseEntity<ContentResponse> create(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody ContentCreateRequest request
    ) {
        ContentResponse response = contentService.create(memberId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<PageResponse<ContentSummaryResponse>> getList(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(contentService.getList(pageable));
    }

    @GetMapping("/{contentId}")
    public ResponseEntity<ContentResponse> get(@PathVariable Long contentId) {
        return ResponseEntity.ok(contentService.get(contentId));
    }
}