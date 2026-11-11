package com.portfolio.contentranking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")          // ← 추가
class ContentRankingSystemApplicationTests {

    @Test
    void contextLoads() {
    }
}