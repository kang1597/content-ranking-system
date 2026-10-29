package com.portfolio.contentranking.like;

import com.portfolio.contentranking.content.Content;
import com.portfolio.contentranking.content.ContentRepository;
import com.portfolio.contentranking.member.Member;
import com.portfolio.contentranking.member.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("좋아요 동시성 테스트")
class LikeServiceConcurrencyTest {

    private static final int REQUEST_COUNT = 100;
    private static final int THREAD_POOL_SIZE = 32;

    @Autowired private LikeService likeService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentLikeRepository contentLikeRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Content content;
    private List<Member> members;

    @BeforeEach
    void setUp() {
        Member author = memberRepository.save(Member.builder()
                .email("author@test.com")
                .password(passwordEncoder.encode("password1234"))
                .nickname("작성자")
                .build());

        content = contentRepository.save(Content.builder()
                .member(author)
                .title("동시성 테스트 콘텐츠")
                .body("100명이 동시에 좋아요를 누른다")
                .build());

        members = new ArrayList<>();
        for (int i = 0; i < REQUEST_COUNT; i++) {
            members.add(memberRepository.save(Member.builder()
                    .email("user" + i + "@test.com")
                    .password(passwordEncoder.encode("password1234"))
                    .nickname("회원" + i)
                    .build()));
        }
    }

    @AfterEach
    void tearDown() {
        // FK 제약 때문에 자식(content_like) → 부모(content) → member 순으로 삭제
        contentLikeRepository.deleteAllInBatch();
        contentRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("서로 다른 회원 100명이 동시에 좋아요를 누르면 like_count는 100이어야 한다")
    void 동시_좋아요_요청시_갱신손실이_발생한다() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch latch = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        Map<String, Integer> failReasons = new ConcurrentHashMap<>();

        long start = System.currentTimeMillis();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            Long memberId = members.get(i).getId();
            executor.submit(() -> {
                try {
                    likeService.like(content.getId(), memberId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    failReasons.merge(rootCauseName(e), 1, Integer::sum);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - start;

        long rowCount = contentLikeRepository.countByContentId(content.getId());
        long likeCount = contentRepository.findById(content.getId())
                .orElseThrow()
                .getLikeCount();

        System.out.println("""
                
                ================ 동시 좋아요 결과 ================
                 요청 수              : %d
                 성공 / 실패          : %d / %d
                 실패 원인            : %s
                 content_like 행 수   : %d   (실제로 좋아요한 사람 수)
                 content.like_count   : %d   (화면에 표시될 숫자)
                 손실된 카운트        : %d
                 소요 시간            : %d ms
                ================================================
                """.formatted(
                REQUEST_COUNT, successCount.get(), failCount.get(), failReasons,
                rowCount, likeCount, rowCount - likeCount, elapsed));

        assertThat(likeCount).isEqualTo(REQUEST_COUNT);
    }

    @Test
    @DisplayName("같은 회원이 동시에 100번 눌러도 좋아요는 1건만 생성된다")
    void 동시_중복좋아요는_DB제약으로_차단된다() throws InterruptedException {
        Long memberId = members.get(0).getId();

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch latch = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        Map<String, Integer> failReasons = new ConcurrentHashMap<>();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            executor.submit(() -> {
                try {
                    likeService.like(content.getId(), memberId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    failReasons.merge(rootCauseName(e), 1, Integer::sum);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        long rowCount = contentLikeRepository.countByContentId(content.getId());

        System.out.println("""
                
                ============== 동시 중복 좋아요 결과 ==============
                 요청 수              : %d
                 성공 / 실패          : %d / %d
                 실패 원인            : %s
                 content_like 행 수   : %d   (1이어야 정상)
                ================================================
                """.formatted(REQUEST_COUNT, successCount.get(), failCount.get(),
                failReasons, rowCount));

        assertThat(rowCount).isEqualTo(1);
    }

    /** 예외 사슬의 맨 아래(진짜 원인) 클래스 이름을 반환한다. */
    private static String rootCauseName(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}
