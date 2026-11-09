package com.portfolio.contentranking.common.scheduler;

import com.portfolio.contentranking.content.ContentRepository;
import com.portfolio.contentranking.content.ViewCountCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Redis 에 쌓인 조회수 증분을 주기적으로 DB 에 반영한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewCountSyncScheduler {

    private final ViewCountCache viewCountCache;
    private final ContentRepository contentRepository;

    @Scheduled(fixedDelayString = "${viewcount.sync-interval-ms:60000}")
    @Transactional
    public void syncViewCounts() {
        Map<Long, Long> deltas = viewCountCache.popAll();
        if (deltas.isEmpty()) {
            return;
        }

        int updated = 0;
        for (Map.Entry<Long, Long> entry : deltas.entrySet()) {
            updated += contentRepository.addViewCount(entry.getKey(), entry.getValue());
        }

        log.info("조회수 동기화 완료. 콘텐츠 {}건, 반영 {}건", deltas.size(), updated);
    }
}