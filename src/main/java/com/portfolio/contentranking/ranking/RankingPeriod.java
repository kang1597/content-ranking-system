package com.portfolio.contentranking.ranking;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public enum RankingPeriod {

    /** 당일 집계. 자정이 지나면 새 키가 시작된다. */
    DAILY {
        @Override
        public String key(LocalDate date) {
            return "ranking:content:daily:" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
        }

        @Override
        public Duration ttl() {
            return Duration.ofDays(2);
        }
    },

    /** 전체 기간 누적. 만료시키지 않는다. */
    ALL {
        @Override
        public String key(LocalDate date) {
            return "ranking:content:all";
        }

        @Override
        public Duration ttl() {
            return null;
        }
    };

    public abstract String key(LocalDate date);

    /** null 이면 만료 없음 */
    public abstract Duration ttl();
}