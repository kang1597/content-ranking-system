package com.portfolio.contentranking.ranking;

/**
 * DB에서 집계한 랭킹 점수. 재구축 입력으로 쓰인다.
 */
public record RankingScore(Long contentId, Long score) {
}