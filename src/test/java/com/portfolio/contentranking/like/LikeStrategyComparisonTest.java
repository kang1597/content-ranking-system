package com.portfolio.contentranking.like;

import com.portfolio.contentranking.content.Content;
import com.portfolio.contentranking.content.ContentRepository;
import com.portfolio.contentranking.like.strategy.LikeStrategy;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DisplayName("좋아요 동시성 전략 비교")
class LikeStrategyComparisonTest {

    private static final int REQUEST_COUNT = 100;
    private static final int THREAD_POOL_SIZE = 32;

    @Autowired private List<LikeStrategy> strategies;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ContentRepository contentRepository;
    @Autowired private ContentLikeRepository contentLikeRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private LikeCountCache likeCountCache;

    private Long authorId;
    private List<Long> memberIds;
    private Long contentId;

    record MeasureResult(
            String strategy,
            int success,
            int fail,
            Map<String, Integer> failReasons,
            long rowCount,
            long likeCount,
            Long redisCount,
            long elapsedMs
    ) {
    }

    @BeforeEach
    void setUp() {
        authorId = memberRepository.save(Member.builder()
                .email("author@test.com")
                .password(passwordEncoder.encode("password1234"))
                .nickname("작성자")
                .build()).getId();

        memberIds = new ArrayList<>();
        for (int i = 0; i < REQUEST_COUNT; i++) {
            memberIds.add(memberRepository.save(Member.builder()
                    .email("user" + i + "@test.com")
                    .password(passwordEncoder.encode("password1234"))
                    .nickname("회원" + i)
                    .build()).getId());
        }
    }

    @AfterEach
    void tearDown() {
        contentLikeRepository.deleteAllInBatch();
        contentRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("전략별로 100건 동시 요청을 보내고 결과를 비교한다")
    void 전략별_동시성_비교() throws InterruptedException {
        List<MeasureResult> results = new ArrayList<>();

        List<LikeStrategy> ordered = strategies.stream()
                .sorted(Comparator.comparing(LikeStrategy::name))
                .toList();

        for (LikeStrategy strategy : ordered) {
            prepareFreshContent();
            results.add(measure(strategy));
        }

        printComparison(results);

        // 비관적 락은 정확해야 한다
        MeasureResult pessimistic = results.stream()
                .filter(r -> r.strategy().startsWith("②"))
                .findFirst()
                .orElseThrow();

        assertThat(pessimistic.likeCount()).isEqualTo(REQUEST_COUNT);
        assertThat(pessimistic.fail()).isZero();
    }

    /** 측정 때마다 like_count 가 0인 새 콘텐츠로 교체한다. */
    private void prepareFreshContent() {
        contentLikeRepository.deleteAllInBatch();
        contentRepository.deleteAllInBatch();

        Member author = memberRepository.findById(authorId).orElseThrow();
        contentId = contentRepository.save(Content.builder()
                .member(author)
                .title("동시성 테스트 콘텐츠")
                .body("100명이 동시에 좋아요를 누른다")
                .build()).getId();

        likeCountCache.evict(contentId);     // ← 추가
    }

    private MeasureResult measure(LikeStrategy strategy) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch latch = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        Map<String, Integer> failReasons = new ConcurrentHashMap<>();

        long start = System.currentTimeMillis();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            Long memberId = memberIds.get(i);
            executor.submit(() -> {
                try {
                    strategy.like(contentId, memberId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                    failReasons.merge(rootCauseName(e), 1, Integer::sum);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - start;

        return new MeasureResult(
                strategy.name(),
                success.get(),
                fail.get(),
                failReasons,
                contentLikeRepository.countByContentId(contentId),
                contentRepository.findById(contentId).orElseThrow().getLikeCount(),
                likeCountCache.find(contentId).orElse(null),    // ← 추가
                elapsed
        );
    }

    private void printComparison(List<MeasureResult> results) {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("================ 동시성 전략 비교 (요청 %d건 / 스레드 %d개) ================\n"
                .formatted(REQUEST_COUNT, THREAD_POOL_SIZE));

        for (MeasureResult r : results) {
            long counted = (r.redisCount() != null && r.likeCount() == 0)
                    ? r.redisCount()          // Redis 가 집계를 맡은 경우
                    : r.likeCount();

            long lost = r.rowCount() - counted;
            String integrity = (lost == 0) ? "✅ 정합성 OK" : "❌ 유실 " + lost;
            String throughput = "%d/%d (%.0f%%)".formatted(
                    r.success(), REQUEST_COUNT, r.success() * 100.0 / REQUEST_COUNT);

            sb.append("""
                    
                     [%s]
                       처리량             : %s
                       실패 원인          : %s
                       content_like 행 수 : %d
                       like_count (DB)    : %d
                       Redis 집계         : %s
                       정합성             : %s
                       소요 시간          : %d ms
                    """.formatted(
                    r.strategy(), throughput, r.failReasons(),
                    r.rowCount(), r.likeCount(),
                    r.redisCount() == null ? "-" : r.redisCount().toString(),
                    integrity, r.elapsedMs()));
        }

        sb.append("==========================================================================\n");
        System.out.println(sb);
    }

    private static String rootCauseName(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }
}