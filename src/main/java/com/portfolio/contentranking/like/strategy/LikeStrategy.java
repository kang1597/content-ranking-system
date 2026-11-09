package com.portfolio.contentranking.like.strategy;

/**
 * 좋아요 카운트 갱신 방식 비교 실험용 인터페이스.
 *
 * 운영 코드(LikeService)는 측정 결과에 따라 ④ DB 원자적 연산을 사용한다.
 * 이 구현들은 측정을 재현할 수 있도록 보존하며,
 * 애플리케이션 흐름에서는 호출되지 않는다.
 * 측정 결과는 README 참고.
 */
public interface LikeStrategy {

    String name();

    void like(Long contentId, Long memberId);
}