package com.portfolio.contentranking.like;

import com.portfolio.contentranking.like.dto.LikeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/contents/{contentId}/likes")
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    @PostMapping
    public ResponseEntity<LikeResponse> like(
            @PathVariable Long contentId,
            @AuthenticationPrincipal Long memberId
    ) {
        return ResponseEntity.ok(likeService.like(contentId, memberId));
    }

    @DeleteMapping
    public ResponseEntity<LikeResponse> unlike(
            @PathVariable Long contentId,
            @AuthenticationPrincipal Long memberId
    ) {
        return ResponseEntity.ok(likeService.unlike(contentId, memberId));
    }

    @GetMapping("/count")
    public ResponseEntity<LikeResponse> getLikeCount(
            @PathVariable Long contentId,
            @AuthenticationPrincipal Long memberId
    ) {
        return ResponseEntity.ok(likeService.getLikeCount(contentId, memberId));
    }
}