package com.portfolio.contentranking.like.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * ③ 낙관적 락 — @Version 으로 충돌을 감지하고, 실패하면 재시도한다.
 *
 * 락을 잡지 않아 대기가 없지만, 경합이 심하면 재시도 비용이 커진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptimisticLockLikeStrategy implements LikeStrategy {

    private static final int MAX_ATTEMPTS = 100;
    private static final long BASE_BACKOFF_MS = 2;

    private final OptimisticLockLikeExecutor executor;

    @Override
    public String name() {
        return "③ 낙관적 락";
    }

    @Override
    public void like(Long contentId, Long memberId) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                executor.likeOnce(contentId, memberId);
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("낙관적 락 재시도 한도 초과. contentId={}, attempts={}",
                            contentId, attempt);
                    throw e;
                }
                backOff(attempt);
            }
        }
    }

    /** 재시도가 동시에 몰리지 않도록 조금씩 늘려가며, 무작위로 흩어 쉰다. */
    private void backOff(int attempt) {
        long maxSleep = BASE_BACKOFF_MS * attempt;
        long sleep = ThreadLocalRandom.current().nextLong(1, maxSleep + 1);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트", ie);
        }
    }
}