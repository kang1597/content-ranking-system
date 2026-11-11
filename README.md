# content-ranking-system

![CI](https://github.com/kang1597/content-ranking-system/actions/workflows/ci.yml/badge.svg)
![Java](https://img.shields.io/badge/Java-17-007396)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1)
![Redis](https://img.shields.io/badge/Redis-7-DC382D)

> **좋아요 수를 비정규화하면서 발생한 갱신 손실을 100개 스레드로 재현하고,
> 6가지 동시성 제어 방식을 동일 조건에서 측정해 비교한 프로젝트입니다.**

---

## 목차

- [핵심 성과 — 동시성 제어 6방식 비교](#핵심-성과--동시성-제어-6방식-비교)
- [프로젝트 배경](#프로젝트-배경)
- [기술 스택](#기술-스택)
- [빠른 시작](#빠른-시작)
- [아키텍처](#아키텍처)
- [도메인 설계](#도메인-설계)
- [API 명세](#api-명세)
- [주요 구현](#주요-구현)
- [트러블슈팅](#트러블슈팅)
- [테스트와 CI](#테스트와-ci)
- [알려진 한계](#알려진-한계)
- [프로젝트 구조](#프로젝트-구조)

---

## 핵심 성과 — 동시성 제어 6방식 비교

**측정 조건**: 요청 100건(서로 다른 회원 100명이 같은 콘텐츠에 좋아요) / 스레드 32개 / HikariCP 풀 10

| 전략 | 처리량 | 정합성 | 소요 시간 | **초당 처리** | 실패 원인 |
| --- | --- | --- | --- | --- | --- |
| ① 조회 후 UPDATE | 12% | ✅ | 2307 ms | 5.2건/s | 데드락 75 + 버전충돌 13 |
| ①′ 순서만 변경 | 10% | ✅ | 1020 ms | 9.8건/s | 버전충돌 90 |
| ② 비관적 락 | 100% | ✅ | 4551 ms | 22.0건/s | — |
| ③ 낙관적 락 | 100% | ✅ | 6465 ms | 15.5건/s | — |
| **④ DB 원자적 연산** | **100%** | **✅** | **2224 ms** | **45.0건/s** | — |
| ⑤ Redis 원자적 연산 | 100% | ✅(Redis) | 427 ms | 234건/s | — |

> ⑤는 Redis 도입 이후 별도 실행에서 측정했습니다.
> 수치는 스레드 스케줄링에 따라 실행마다 달라지므로, **의미 있는 것은 절대값이 아니라 상대 순위와 정합성 여부**입니다.

### ④ DB 원자적 연산을 채택한 이유

```sql
UPDATE content SET like_count = like_count + 1 WHERE id = ?
```

**애플리케이션이 값을 읽지 않습니다.** "현재 값이 뭐든 거기에 1을 더해라"를 DB에게 맡깁니다.

```
[①~③]  읽기(앱) → 계산(앱) → 쓰기(DB)   ← 세 단계, 사이에 틈
[④]                            쓰기(DB)   ← 한 단계, 틈 없음
```

②보다 2배 빠른 이유는 **락 보유 시간**입니다. 비관적 락은 SELECT 시점에 락을 잡고 커밋까지 들고 있는데, 그 사이 애플리케이션이 계산하고 INSERT하는 시간까지 다른 요청이 대기합니다. 원자적 연산은 UPDATE 실행 동안만 잡습니다.

> **락을 짧게 잡는 것이 동시성 성능의 핵심이고, 가장 짧게 잡는 방법은 아예 잡지 않고 DB에게 맡기는 것입니다.**

⑤ Redis가 7배 빨랐지만 채택하지 않았습니다. **좋아요 수는 사용자가 바로 알아차리는 값이라 진실이 휘발성 저장소에 있으면 안 되기** 때문입니다. 대신 전환 임계점을 문서로 남겼습니다 — *단일 콘텐츠에 초당 30건 이상의 좋아요가 지속되면 ⑤를 검토한다.*

### ⭐ 가장 중요한 발견 — 조용한 실패가 더 위험하다

①′는 데드락을 **완전히 없앴습니다.** 100건 전부 "성공", 에러율 0%. 그런데 `like_count`는 10이었습니다.

| | ① | ①′ |
| --- | --- | --- |
| 사용자가 겪는 일 | 좋아요가 안 눌림, 에러 표시 | **눌렸다고 나오는데 수가 안 오름** |
| 모니터링 | 에러율 79% → **즉시 알람** | 에러율 0% → **아무도 모름** |
| 발견 시점 | 즉시 | 데이터가 틀어진 뒤 누군가 신고할 때 |

**"데드락을 없앴다"가 개선이 아니었습니다.** 증상만 가리고 본질(읽고-계산하고-쓰는 틈)은 그대로 둔 채 **발견을 어렵게 만들었습니다.**

`@Version` 한 줄을 추가하자 **조용한 유실 90건이 명시적 실패 90건**으로 바뀌었습니다.

| | `@Version` 전 | `@Version` 후 |
| --- | --- | --- |
| 성공 / 실패 | **100 / 0** | 10 / 90 |
| `content_like` 행 | 100 | 10 |
| `like_count` | **10** | **10** |
| 데이터 정합성 | **❌ 90건이 거짓** | **✅ 완전히 일치** |

숫자 `10`은 똑같은데 의미가 정반대입니다. 처리량은 떨어졌지만 **데이터는 더 이상 거짓말하지 않습니다.**

> **틀린 결과를 조용히 내놓느니, 실패를 알리고 호출자가 판단하게 한다.**

---

## 프로젝트 배경

콘텐츠 목록에서 좋아요 수를 보여주려면 매번 세면 됩니다.

```sql
SELECT COUNT(*) FROM content_like WHERE content_id = ?
```

정합성은 완벽하지만, 목록 20건마다 COUNT를 하면 쿼리가 21번 나가고 좋아요가 수십만 건이면 COUNT 자체가 느려집니다. 그래서 `content.like_count` 컬럼에 값을 복제하는 **비정규화**를 택했습니다.

| | 매번 COUNT | `like_count` 컬럼 |
| --- | --- | --- |
| 조회 성능 | 느림 | **빠름** |
| 정합성 | 항상 정확 | **동기화 실패 시 틀어짐** |
| 동시성 | 문제없음 | **갱신 손실 발생** |

**성능을 얻는 대가로 정합성 관리 책임이 애플리케이션으로 넘어옵니다.** 이 프로젝트의 전부가 여기서 출발합니다.

### 진행 방식 — 일부러 틀린 구현부터

```
4단계  일부러 "틀린" 구현을 만든다        (읽고 → 계산하고 → 쓴다)
5단계  100개 스레드로 깨뜨린다            → 100건 중 9건만 반영됨
6단계  6가지 해결책을 같은 조건에서 측정  → 비교표 완성
7단계  Redis 캐싱 — 정확성 요구가 다른 두 카운터를 다르게 처리
8단계  랭킹 — 파생 데이터를 재생성 가능하게 설계
9단계  테스트 격리 / CI / 설정 외부화
```

바로 올바른 구현을 쓰면 **문제를 겪지 않아 왜 그 해법이 필요한지 설명할 수 없고**, 비교할 기준점도 없습니다. 커밋 이력에 "문제를 드러냄 → 해결함"이라는 흐름이 그대로 남아 있습니다.

---

## 기술 스택

| 분류 | 기술 | 선정 이유 |
| --- | --- | --- |
| Java | 17 | LTS, record·텍스트 블록·패턴 매칭 사용 |
| Spring Boot | 4.1.1 | Spring Framework 7.0.9 / Security 7.1.1 |
| ORM | Hibernate 7.4.5 | Jakarta Persistence 3.2.0 |
| DB | MySQL 8.0.46 | **InnoDB의 락·MVCC 동작 관찰이 프로젝트 주제** |
| 캐시 | Redis 7 | 단일 스레드 원자성, Sorted Set |
| Redis 클라이언트 | Lettuce | **커넥션이 스레드 안전** → 동시 요청에 유리 |
| 인증 | jjwt 0.12.6 | api / impl / jackson 3종 분리 (SPI) |
| 인프라 | Docker Compose | MySQL + Redis + 테스트 전용 MySQL |
| CI | GitHub Actions | service container |

### 측정을 위해 미리 고정한 설정

| 설정 | 값 | 이유 |
| --- | --- | --- |
| `open-in-view` | **false** | `true`면 영속성 컨텍스트가 응답까지 열려 **커넥션을 점유**. 100스레드 실험에서 측정 대상이 "락 경합"이 아니라 **"커넥션 풀 대기"** 가 되어버림 |
| `rewriteBatchedStatements` | true | **측정 후에 켜면** 락 방식 차이인지 배치 설정 차이인지 구분 불가 |
| `default_batch_fetch_size` | 100 | N+1 전역 안전망 |
| 이미지 태그 | `mysql:8.0` | `latest` 금지 — **clone해서 돌릴 수 있어야 함** |

---

## 빠른 시작

```bash
git clone https://github.com/kang1597/content-ranking-system.git
cd content-ranking-system

cp .env.example .env     # 로컬 개발용 기본값 그대로 사용 가능

docker compose up -d
docker compose ps        # 세 컨테이너가 healthy 될 때까지 (약 30초)

./gradlew bootRun
```

**환경변수를 따로 설정하지 않아도 실행됩니다.** 모든 설정이 `${ENV:로컬기본값}` 형태라 기본값으로 동작합니다.

| 포트 | 용도 |
| --- | --- |
| 8081 | 애플리케이션 |
| 3308 | MySQL (개발) |
| 3309 | MySQL (테스트 전용) |
| 6379 | Redis |

### 동작 확인

`api-test.http`를 IntelliJ에서 열어 위에서부터 실행하면 됩니다(별도 도구 불필요).

```http
GET http://localhost:8081/actuator/health
GET http://localhost:8081/actuator/info
```

### 테스트

```bash
./gradlew test               # 검증용 (CI가 실행하는 것)
./gradlew measurementTest    # 전략별 성능 비교 (수동 실행)
```

---

## 아키텍처

```
                        ┌─────────────────────────────────────┐
   HTTP 요청 ─────────→ │  JwtAuthenticationFilter            │  인증
                        │   Bearer 토큰 → SecurityContext     │
                        ├─────────────────────────────────────┤
                        │  AuthorizationFilter                │  인가
                        └──────────────┬──────────────────────┘
                                       ↓
                        ┌─────────────────────────────────────┐
                        │  Controller  (얇음)                  │
                        ├─────────────────────────────────────┤
                        │  Service     (@Transactional)        │
                        │   ★ DTO 변환도 여기서 (OSIV off)     │
                        ├─────────────────────────────────────┤
                        │  Repository                          │
                        └───────┬──────────────────┬──────────┘
                                ↓                  ↓
                        ┌───────────────┐   ┌──────────────────┐
                        │   MySQL       │   │   Redis          │
                        │  진실의 원천   │   │  파생 데이터      │
                        │               │   │                  │
                        │ member        │   │ 좋아요 수 캐시    │
                        │ content       │   │ 조회수 증분       │
                        │ content_like  │   │ 랭킹 Sorted Set  │
                        └───────────────┘   └──────────────────┘
                                ↑                  │
                                └──── 스케줄러 ─────┘
                                   1분마다 조회수 증분 반영
                                   랭킹 유실 시 DB에서 재구축
```

**아래쪽 화살표가 핵심입니다.** Redis에 있는 모든 데이터는 MySQL에서 다시 만들 수 있습니다. 그래서 Redis는 **날아가도 되는 저장소**입니다.

---

## 도메인 설계

```
┌──────────────┐          ┌────────────────┐         ┌──────────────┐
│   member     │          │  content_like  │         │   content    │
├──────────────┤          ├────────────────┤         ├──────────────┤
│ id       PK  │◄────┐    │ id         PK  │    ┌───►│ id       PK  │
│ email    UK  │     └────┤ member_id  FK  │    │    │ member_id FK │──┐
│ password     │          │ content_id FK  ├────┘    │ title        │  │
│ nickname     │          │ created_at ★   │         │ body (text)  │  │
└──────────────┘          └────────────────┘         │ like_count ★ │  │
        ▲                  UK(content_id,            │ view_count ★ │  │
        │                     member_id)             │ version   ★  │  │
        └────────────────────────────────────────────┴──────────────┘◄─┘
```

### 주요 설계 결정

| 결정 | 이유 |
| --- | --- |
| **`@ManyToMany` 대신 `ContentLike` 엔티티** | 중간 테이블에 컬럼을 못 넣음. **일간 랭킹에 `created_at`이 필수** |
| **복합 유니크 제약** `(content_id, member_id)` | 애플리케이션 `existsBy`만으로는 동시 요청을 못 막음(TOCTOU). DB 제약이 **최후의 방어선** |
| **단방향 연관관계만** | 양방향은 편의 하나를 얻고 순환 참조·주인 관리·컬렉션 로딩 위험을 떠안음 |
| **모든 연관관계 LAZY 명시** | `@ManyToOne` 기본값이 **EAGER**라 명시하지 않으면 N+1이 자동 발생 |
| **Setter 없음** | `setLikeCount(-5)` 같은 호출을 막고 의미 있는 메서드만 공개 |
| **`@DynamicUpdate`** | 아직 글 수정 기능이 없지만, **만드는 순간 조용히 생길 결함**을 미리 차단 |

<details>
<summary><b>@DynamicUpdate 가 지금 필요한 이유</b></summary>

Hibernate 기본 동작은 **모든 컬럼을 UPDATE**합니다.

```
T1: 글 수정 요청 → content 읽음 (like_count = 50)
T2: 좋아요 → UPDATE SET like_count = like_count + 1 → 51
T1: 제목만 바꿔 저장 → UPDATE SET title=?, like_count=50, ... ← 51을 50으로 되돌림!
```

**`@Version`이 막아주지 않습니다** — ④의 벌크 UPDATE는 버전을 올리지 않으므로 T1 입장에서는 아무도 안 건드린 것처럼 보입니다.

①′에서 배운 대로 **조용한 데이터 손상은 발견이 가장 늦습니다.** 구조적 위험을 아는 시점에 막아뒀습니다.
</details>

<details>
<summary><b>복합 인덱스 Leftmost Prefix 원칙이 DDL 에 드러난 사례</b></summary>

MySQL은 FK 컬럼에 인덱스를 요구하는데, 생성된 DDL은 이렇습니다.

```sql
UNIQUE KEY `uk_content_like_content_member` (`content_id`,`member_id`),
KEY `FKmoavhmr49wqnqq1kyia3hapa` (`member_id`),   ← member_id 용만 추가 생성
```

| FK 컬럼 | 인덱스 상황 | MySQL의 처리 |
| --- | --- | --- |
| `content_id` | UK의 **왼쪽 첫 컬럼** | 기존 인덱스로 충분 → **추가 안 함** |
| `member_id` | UK의 두 번째 컬럼 | 단독 조회 불가 → **인덱스 자동 생성** |

덤으로 "내가 좋아요한 글 목록"(`WHERE member_id = ?`) 기능을 나중에 추가해도 인덱스가 이미 있습니다.
</details>

---

## API 명세

| Method | Endpoint | 인증 | 성공 | 실패 |
| --- | --- | --- | --- | --- |
| `POST` | `/api/auth/signup` | — | 201 | 400 / **409** 이메일 중복 |
| `POST` | `/api/auth/login` | — | 200 + 토큰 | 400 / 401 |
| `POST` | `/api/contents` | ✅ | 201 | 401 |
| `GET` | `/api/contents` | — | 200 (페이징) | — |
| `GET` | `/api/contents/{id}` | — | 200 | 404 |
| `POST` | `/api/contents/{id}/likes` | ✅ | 200 | 401 / **409** / 404 |
| `DELETE` | `/api/contents/{id}/likes` | ✅ | 200 | 401 / 404 |
| `GET` | `/api/contents/{id}/likes/count` | — | 200 | 404 |
| `GET` | `/api/rankings/contents?period=&limit=` | — | 200 | 400 |
| `POST` | `/api/rankings/rebuild` | ✅ | 200 | 401 |
| `GET` | `/actuator/health`, `/actuator/info` | — | 200 | — |

### 보안 규칙

```java
.requestMatchers("/error").permitAll()
.requestMatchers("/actuator/health", "/actuator/info").permitAll()
.requestMatchers("/api/auth/**").permitAll()
.requestMatchers(HttpMethod.GET, "/api/contents/**").permitAll()
.requestMatchers(HttpMethod.GET, "/api/rankings/**").permitAll()   // GET만
.anyRequest().authenticated()
```

**HTTP 메서드까지 지정한 선택**이 8단계에서 값을 했습니다. `POST /api/rankings/rebuild`(DB 집계를 유발하는 무거운 작업)가 자동으로 인증 뒤로 보호됐습니다.

---

## 주요 구현

### 좋아요 — TOCTOU 2중 방어

```java
@Transactional
public LikeResponse like(Long contentId, Long memberId) {

    // 1차 방어 — 대부분의 중복을 걸러 친절한 에러를 준다
    if (contentLikeRepository.existsByContentIdAndMemberId(contentId, memberId)) {
        throw new AlreadyLikedException();          // → 409
    }

    // ★ UPDATE 를 INSERT 보다 먼저 — FK 공유 락으로 인한 데드락 회피
    //   반환값 0 = 그런 콘텐츠가 없음 → 별도 조회 없이 존재 확인까지 겸함
    if (contentRepository.increaseLikeCount(contentId) == 0) {
        throw new ContentNotFoundException(contentId);
    }

    try {
        contentLikeRepository.save(...);
        contentLikeRepository.flush();
    } catch (DataIntegrityViolationException e) {
        // 2차 방어 — 동시 요청으로 1차를 뚫었을 때 DB 유니크 제약이 잡는다
        throw new AlreadyLikedException();          // → 409 (500 이 아니라)
    }

    likeCountCache.evictAfterCompletion(contentId);  // 커밋 후 캐시 삭제
    contentRankingRepository.addScore(contentId, 1); // 랭킹 ZINCRBY
    ...
}
```

| 계층 | 역할 |
| --- | --- |
| 애플리케이션 `existsBy` | **대부분을 걸러** 친절한 에러 메시지 |
| DB 유니크 제약 | **동시 요청이라도 반드시 막는** 최후의 방어선 |

동시 중복 요청 100건 테스트 결과 `content_like` 행 1건, 나머지 99건은 `AlreadyLikedException`(409)으로 처리됩니다.

### 캐싱 — 요구사항이 다르면 설계도 다르다

같은 "카운터"인데 전혀 다르게 구현했습니다.

| | 좋아요 수 | 조회수 |
| --- | --- | --- |
| 진실의 원천 | **DB** | **DB** (최대 1분 지연) |
| Redis 역할 | **읽기 캐시** | **쓰기 버퍼** |
| 쓰기 경로 | DB 원자적 UPDATE → 캐시 삭제 | Redis `INCR`만 (DB는 배치) |
| 유실 시 | 영향 없음 | 최대 1분치 |

**좋아요 수가 틀리면 사용자가 바로 알아차리지만, 조회수가 몇 건 틀리는 건 아무도 모릅니다.** 먼저 정할 것은 "이 데이터는 얼마나 정확해야 하는가"입니다.

<details>
<summary><b>캐시 설계 원칙 4가지</b></summary>

**① 갱신이 아니라 삭제** — 갱신은 쓰기 순서가 뒤집히면 낡은 값이 남습니다. 삭제는 "다음에 읽는 사람이 DB에서 가져가라"는 뜻이라 순서 문제가 없고 멱등합니다.

**② 커밋 이후에 삭제** — `TransactionSynchronizationManager`의 `afterCompletion`. 트랜잭션 안에서 지우면 커밋 전에 다른 요청이 **커밋되지 않은 값**을 캐시에 다시 넣을 수 있습니다.

**③ TTL은 안전망** — 무효화를 놓쳐도 피해가 10분으로 한정됩니다. TTL에 의존해 무효화를 생략해서도, 무효화했다고 TTL을 빼서도 안 됩니다.

**④ Graceful Degradation** — 모든 Redis 호출을 try/catch로 감싸 실패 시 DB 경로로 넘어갑니다. **캐시는 속도를 위한 것이지 기능을 위한 것이 아닙니다.**

**조회수에 "전체 값"이 아니라 "증분"을 저장한 이유**: Redis가 날아가도 **마지막 동기화 이후 1분치만** 잃습니다. 유실의 범위를 설계로 제한한 것입니다. 수거는 `GETDEL`(원자적), 키 순회는 `SCAN`(단일 스레드 블로킹 회피)을 씁니다.
</details>

### 랭킹 — 파생 데이터는 재생성 가능해야 한다

| | RDBMS `ORDER BY` | Sorted Set |
| --- | --- | --- |
| 정렬 시점 | **조회할 때마다** | **삽입할 때 유지** |
| 기간별 집계 | `WHERE created_at BETWEEN` + `GROUP BY` | **키를 나누면 끝** |

전체 랭킹만이면 SQL로도 충분합니다. **결정적인 것은 일간 랭킹**입니다 — 좋아요 수백만 건을 매번 날짜로 집계해야 하는데, Sorted Set은 날짜별 키에 집계가 이미 끝나 있습니다.

**문제**: `ZINCRBY`는 좋아요가 새로 눌릴 때만 호출됩니다. Redis가 비면 **과거 좋아요가 랭킹에서 영원히 사라집니다.**

**해결**: DB에서 재구축합니다.

```
❌ DEL key; ZADD key ...      ← 두 명령 사이에 빈 랭킹이 노출됨

✅ ZADD   tempKey(+UUID) ...   ← 기존 키는 그대로
   EXPIRE tempKey ttl          ← RENAME 이 TTL 을 보존하므로 미리 건다
   RENAME tempKey key          ← 원자적 교체
```

조회하는 쪽은 **이전 랭킹 또는 새 랭킹**만 보고, 빈 랭킹은 절대 보지 않습니다. 블루-그린 배포, 정적 사이트 배포(심볼릭 링크 교체)와 같은 발상입니다.

**검증 — `FLUSHDB`로 Redis를 통째로 날려봤습니다.**

| 호출 | 응답 시간 | 내용 |
| --- | --- | --- |
| 재구축 전 | 20 ms | Redis 조회만, 빈 결과 |
| **재구축 트리거** | **450 ms** | 키 없음 감지 → DB 집계 → ZADD → RENAME → 재조회 |
| 재구축 후 | **30 ms** | Redis 캐시 히트 |

**한 번의 느린 요청이 이후 모든 요청을 다시 빠르게 만듭니다.**

재구축 경로가 있으면 Redis는 **버려도 되는 저장소**가 됩니다 — 영속성 설정도, 백업도, eviction 걱정도 필요 없습니다. **설계 하나가 운영 요구사항을 통째로 지웠습니다.**

---

## 트러블슈팅

<details>
<summary><b>① 데드락 80/100 — FK 공유 락</b></summary>

동시성 테스트에서 100건 중 **80건이 `MySQLTransactionRollbackException`(error 1213)** 으로 실패했습니다. 갱신 손실만 예상했는데 성공 자체가 20건뿐이었습니다.

**원인** — INSERT가 FK 무결성 검사를 위해 부모 행에 **공유 락(S)** 을 겁니다.

```
T1: INSERT → content#8 에 S 락 획득
T2: INSERT → content#8 에 S 락 획득   (S끼리는 공존 가능)

T1: UPDATE → X 락 필요 → T2의 S 락이 풀리길 기다림
T2: UPDATE → X 락 필요 → T1의 S 락이 풀리길 기다림
                          ↑ 순환 대기 = 데드락
```

**락 승격 데드락(lock upgrade deadlock)** 입니다.

**해결** — UPDATE를 먼저 실행해 X 락을 선점합니다. 두 번째 트랜잭션은 그 자리에서 대기만 하고, 순환이 없으므로 데드락이 없습니다.

**의미** — 갱신 손실은 "숫자가 틀리는" 문제지만, 데드락은 **"좋아요가 아예 안 눌리고 500 에러"** 입니다. 순진한 구현은 부정확하기만 한 게 아니라 **아예 불안정**했습니다.
</details>

<details>
<summary><b>② 측정 지표를 잘못 고르면 결론이 뒤집힌다</b></summary>

| 지표 | ①′ | ④ | 결론 |
| --- | --- | --- | --- |
| **총 소요 시간** | 1020 ms | 2224 ms | ①′가 빠름 ❌ |
| **초당 처리** | 9.8건/s | 45.0건/s | ④가 빠름 ✅ |

**①′는 10건만 처리하고 멈췄을 뿐입니다.** 처리량이 다른 방식끼리는 총 시간이 아니라 **단위 시간당 처리 건수**로 비교해야 합니다.

출력에 `like_count : 10   ❌ 유실 0` 이라는 **모순된 표시**가 나온 것이 발견의 계기였습니다. **정합성(틀렸는가)** 과 **처리량(얼마나 처리했는가)** 을 하나로 섞었던 것입니다.

측정 도구 자체에도 같은 함정이 있었습니다 — `successCount++`는 **읽기-계산-쓰기 세 단계**라 우리가 재현하려는 문제와 구조가 같습니다. `AtomicInteger`(CAS)와 `ConcurrentHashMap.merge()`를 썼습니다.
</details>

<details>
<summary><b>③ ERROR 디스패치로 404 가 403 이 되다</b></summary>

`permitAll`로 열어둔 경로가 403을 반환했습니다.

```
GET /api/contents
  → Security: permitAll → 통과 ✅
  → DispatcherServlet: 매핑된 컨트롤러 없음 → 404
  → Spring Boot 가 /error 로 내부 전달 (ERROR 디스패치)
  → Security 가 이 전달도 한 번 더 검사 ❗
  → /error 는 어느 permitAll 규칙에도 안 맞음 → 403
```

Spring Boot는 보안 필터를 `REQUEST`뿐 아니라 **`ERROR`, `ASYNC` 디스패치에도 등록**합니다. 에러 페이지를 통한 보안 우회를 막기 위한 조치인데, 부작용으로 **모든 404가 403으로 둔갑**합니다.

**해결**: `.requestMatchers("/error").permitAll()`
</details>

<details>
<summary><b>④ catch-all 예외 핸들러가 404 를 500 으로</b></summary>

`@ExceptionHandler(Exception.class)`를 추가한 직후 정상적인 404가 500이 됐습니다.

```
[기본]        NoResourceFoundException → Spring 내부 처리기 → 404
[핸들러 추가] NoResourceFoundException → 우리 핸들러      → 500 ❌
```

같은 식으로 망가질 것들: `HttpRequestMethodNotSupportedException`(405), `HttpMessageNotReadableException`(400)...

**해결**: `ResponseEntityExceptionHandler`를 상속해 **프레임워크 예외는 부모가 처리**하게 하고, 우리는 도메인 예외만 다룹니다. `MethodArgumentNotValidException`은 부모가 이미 등록해뒀으므로 `@ExceptionHandler`가 아니라 **`@Override`** 여야 합니다(중복 등록 시 기동 실패).

**일반화** — **넓게 잡는 코드는 좁게 잡는 코드가 먼저 동작할 수 있을 때만 안전합니다.** 같은 코드가 이전 프로젝트에서는 문제가 없었는데, 거기선 모든 경로에 컨트롤러가 있어 404가 날 일이 없었기 때문입니다.
</details>

<details>
<summary><b>⑤ N+1 발견과 해결</b></summary>

| 상태 | 쿼리 수 |
| --- | --- |
| 아무것도 안 함 | 1(목록) + 1(count) + **N**(작성자 20번) |
| `default_batch_fetch_size: 100` | 1 + 1 + **1** (`WHERE id IN (...)`) |
| **`@EntityGraph`** | **1(JOIN) + 1(count)** |

0단계에 전역 안전망을 깔아둔 덕분에 최악(1+N)은 처음부터 피했고, SQL 로그로 확인한 뒤 `@EntityGraph`로 명시적으로 해결했습니다.

`@ManyToOne`이라 fetch join + 페이징을 **행 뻥튀기 없이** 안전하게 쓸 수 있었습니다 — 양방향 연관을 만들지 않은 판단이 여기서 이득이 됐습니다.
</details>

<details>
<summary><b>⑥ 재구축 무한 반복 — 캐시 관통</b></summary>

처음 구현한 `rebuild()`는 집계 결과가 0건이면 키를 삭제하고 끝냈습니다.

```
조회 → 키 없음 → 재구축 → 0건 → 키 삭제 → (다음 조회) → 키 없음 → 재구축 → ...
```

랭킹 조회가 들어올 때마다 DB 집계 쿼리가 돕니다. **캐시 관통(cache penetration)** 입니다.

데이터가 있는 동안에는 드러나지 않습니다. **다음 날 아침 첫 조회**처럼 "그날 좋아요가 아직 0건"인 상황에서 터집니다.

**해결** — 60초 TTL의 쿨다운 표식 키를 남깁니다. "결과가 없다"는 것도 **알아낸 정보**이므로 기록해야 합니다.

```java
public boolean needsRebuild(RankingPeriod period) {
    if (hasKey(key)) return false;              // 랭킹이 살아 있다
    return !hasKey(rebuiltMarkerKey(key));      // 최근에 시도했으면 하지 않는다
}
```
</details>

<details>
<summary><b>⑦ CI 가 숨어 있던 환경 의존성을 찾아냈다</b></summary>

테스트 전용 DB를 분리하고 CI를 붙이자 `ContentRankingSystemApplicationTests`만 실패했습니다. `@ActiveProfiles("test")`가 빠져 있어 개발용 DataSource(3308)에 붙으려 한 것입니다.

```
[내 PC]  3308 개발 DB 떠 있음  →  통과  →  "문제없네"
[CI]     3308 없음             →  실패  →  "테스트가 개발 DB 에 의존하고 있었네"
```

**로컬에서는 영원히 못 찾았을 문제입니다.** CI의 "매번 깨끗한 상태"가 바로 자기 가치를 증명한 사례입니다.

에러 메시지는 `Unable to determine Dialect`였는데, 이는 **원인이 아니라 결과**입니다. Hibernate는 DB에 접속해 `DatabaseMetaData`로 제품명을 읽어 Dialect를 정하므로, 연결이 안 되면 못 정하는 것뿐입니다. 조언대로 Dialect를 명시하면 **에러 메시지만 바뀌고 연결 실패는 그대로**입니다.
</details>

<details>
<summary><b>⑧ 테스트가 개발 DB 를 파괴하고 있었다</b></summary>

| 테이블 | AUTO_INCREMENT | 실제 행 수 |
| --- | --- | --- |
| `member` | **1218** | 4 |
| `content_like` | **2089** | 7 |

동시성 테스트의 `@AfterEach`가 `deleteAllInBatch()`로 전체를 비우는데, 그 대상이 **개발 DB**였습니다. 수동 API 테스트로 만든 데이터가 테스트를 돌릴 때마다 사라졌습니다.

`AUTO_INCREMENT`는 삭제로 되돌아가지 않으므로 **지워진 1,214건의 흔적이 남아 있었습니다.**

**해결** — 테스트 전용 MySQL 컨테이너(3309, tmpfs)와 Redis 논리 DB 1번으로 분리했습니다.

**H2를 쓰지 않은 이유**: FK 공유 락, MVCC 스냅샷 읽기, 데드락 감지 등 **InnoDB 고유 동작이 이 프로젝트 측정의 전제**입니다. H2에서 통과시키면 "H2에서는 동작한다"는 것만 증명됩니다.
</details>

---

## 테스트와 CI

### 테스트 환경 분리

| | 개발 | 테스트 |
| --- | --- | --- |
| MySQL | 3308, 영속 볼륨 | **3309, tmpfs(RAM)** |
| Redis | DB 0번 | **DB 1번** |
| `ddl-auto` | `update` | **`create-drop`** |
| 스케줄러 | 동작 | **`@Profile("!test")` 로 차단** |

**tmpfs**로 테스트 DB를 RAM에 두어 매 기동마다 초기화되고 디스크 I/O가 사라집니다. 스케줄러를 끈 이유는 조회수 동기화가 **동시성 측정 중에 락 경합과 시간에 섞이기** 때문입니다.

### 테스트 분류

| 명령 | 실행 대상 |
| --- | --- |
| `./gradlew test` | 검증용 (CI) |
| `./gradlew measurementTest` | `@Tag("measurement")` — 전략 비교 |

**비결정적이고 느린 측정 테스트를 CI에 넣지 않았습니다.** 가끔 실패하는 테스트가 있으면 사람들이 **빨간불을 무시하기 시작**하고, 그러면 진짜 실패도 묻힙니다.

### CI (GitHub Actions)

MySQL과 Redis를 **service container**로 기동합니다. `docker compose up`을 step에 넣는 것과 달리 **헬스체크 통과까지 자동으로 대기**하므로 sleep/polling이 필요 없습니다.

```yaml
services:
  mysql:
    image: mysql:8.0
    ports: [ "3309:3306" ]
    options: >-
      --health-cmd="mysqladmin ping -h localhost -uroot -proot1234"
      --health-interval=10s
```

포트를 로컬과 동일한 3309로 맞춰 **`application-test.yml`을 그대로 재사용**합니다.

---

## 알려진 한계

> 몰라서 안 한 것과 알고 범위를 그은 것은 다릅니다. 아래는 후자입니다.

| # | 한계 | 왜 안 했나 | 보완 방법 |
| --- | --- | --- | --- |
| 1 | **리프레시 토큰 미구현** | 주제가 동시성이지 인증이 아님 | 짧은 액세스 + DB 저장 리프레시 |
| 2 | 로그인 **타이밍 공격** 미대응 | 이메일이 없으면 BCrypt 비교를 건너뛰어 응답이 빨라짐 | 더미 해시로 시간 맞추기 |
| 3 | 회원가입 시 **이메일 중복 노출** | 안 보여주면 가입 불가 | 이메일 인증 |
| 4 | 캐시 스탬피드 **분산 락 미적용** | Lua 소유권 검증, 락 연장, Redlock 논쟁까지 가면 주제에서 벗어남 | `SETNX` + TTL, Redisson |
| 5 | 관리 API **역할 기반 인가 미적용** | 권한 체계 자체가 없음 | `@PreAuthorize("hasRole('ADMIN')")` |
| 6 | 당일 랭킹은 **근사** | 벌크 DELETE라 취소 전 `createdAt`을 알 수 없음 | 취소 경로에 조회 1회 추가 |
| 7 | 동점 처리 정책 없음 | 명세하지 않음 | 점수에 시간 혼합 |
| 8 | 조회수 **봇·새로고침 중복 집계** | — | IP/세션 단위 중복 제거 |
| 9 | 배치 동기화 중 크래시 시 **최대 1분 유실** | 허용 범위로 판단 | 주기 단축 또는 WAL |
| 10 | 재구축 **10만 건 이상 배치 분할 필요** | 현 규모에서 불필요 | 파이프라인, 청크 ZADD |
| 11 | 에러 포맷 두 가지 (`ErrorResponse` / RFC 9457) | 발견이 늦음 | 통일 |
| 12 | `ddl-auto: update` 사용 중 | 개발 편의 | **`none` + Flyway** |
| 13 | 헬스체크에 Redis 포함 | — | liveness/readiness 그룹 분리 |

<details>
<summary><b>12. ddl-auto 를 운영에서 쓰면 안 되는 이유 — 세 번의 실증</b></summary>

| # | 증상 | 조치 |
| --- | --- | --- |
| 1 | `@Lob String`이 **`tinytext`(255B)** 로 생성 — Hibernate 6부터 `@Column(length)`로 타입을 고름 | `length=65535` 지정 후 `down -v` |
| 2 | `@Version` 추가 시 **기존 행이 NULL** | `down -v` |
| 3 | `drop index` 실패를 **조용히 무시** (에러 로그조차 없음) | — |

| 동작 | `update`가 하는가 |
| --- | --- |
| 테이블/컬럼 추가 | ✅ |
| **컬럼 타입 변경** | ❌ |
| 컬럼 삭제 / 제약 변경 | ❌ |

운영에서는 실 데이터가 있어 `down -v`가 불가능하고, **의도한 변경이 적용됐는지 확신할 수 없습니다.** `validate` + Flyway/Liquibase로 스키마를 버전 관리해야 합니다.

`application-prod.yml`에는 이미 `ddl-auto: validate`를 적용했습니다.
</details>

<details>
<summary><b>13. 헬스체크와 Graceful Degradation 의 충돌</b></summary>

`/actuator/health`는 클래스패스를 보고 **자동으로 Redis를 검사**합니다. 그런데 우리 설계는 "Redis가 죽어도 DB로 동작한다"인데, 헬스체크가 `DOWN`을 내보내면 **로드밸런서가 멀쩡한 인스턴스를 빼버릴 수** 있습니다.

| 엔드포인트 | 포함 | 실패 시 조치 |
| --- | --- | --- |
| `/actuator/health` | 전부 | 사람이 보는 종합 상태 |
| `/actuator/health/liveness` | `livenessState`만 | **프로세스 재시작** |
| `/actuator/health/readiness` | `readinessState`만 | **트래픽에서 제외** |

**Liveness에 외부 의존성을 넣으면 재앙입니다.** DB가 잠깐 끊겼다고 모든 인스턴스를 재시작하면, DB가 돌아왔을 때 전부 동시에 재기동하며 커넥션 폭풍이 일어납니다.

쿠버네티스에 올린다면 프로브를 루트가 아니라 **그룹 엔드포인트**에 걸어야 합니다.
</details>

---

## 프로젝트 구조

```
com.portfolio.contentranking
│
├── common/
│   ├── BaseTimeEntity.java
│   ├── config/{JpaAuditingConfig, SecurityConfig, SchedulingConfig}
│   ├── dto/PageResponse.java
│   ├── exception/{ErrorResponse, GlobalExceptionHandler, 도메인 예외 6종}
│   └── scheduler/ViewCountSyncScheduler.java
│
├── auth/                              ← member 와 분리 (관심사가 다름)
│   ├── JwtProvider / JwtAuthenticationFilter / JwtAuthenticationEntryPoint
│   └── AuthService / AuthController / dto
│
├── member/
├── content/
│   ├── Content / ContentRepository / ContentService / ContentController
│   └── ViewCountCache.java            ← Redis 조회수 증분
│
├── like/
│   ├── LikeService.java               ★ 운영 코드 (④ 채택)
│   ├── LikeCountCache.java            ← Redis 좋아요 수 캐시
│   └── strategy/                      ★ 실험용 — 측정 재현을 위해 보존
│       ├── LikeStrategy.java              인터페이스
│       ├── NaiveLikeStrategy.java         ① 조회 후 UPDATE
│       ├── ReorderedLikeStrategy.java     ①′ 순서만 변경
│       ├── PessimisticLockLikeStrategy    ② 비관적 락
│       ├── OptimisticLockLike{Executor,Strategy}  ③ 낙관적 락
│       ├── AtomicUpdateLikeStrategy       ④ DB 원자적 연산
│       └── RedisAtomicLikeStrategy        ⑤ Redis INCR
│
└── ranking/
    ├── RankingPeriod.java             DAILY(TTL 2일) / ALL
    ├── ContentRankingRepository       Sorted Set 접근
    ├── RankingRebuildService          DB → Redis 재구축
    └── RankingService / RankingController
```

**기능별 패키지(Package by Feature)** 입니다. 하나의 기능을 수정할 때 한 폴더 안에서 완결되고, 모듈을 떼어낼 때 경계가 이미 그어져 있습니다.

`strategy/` 패키지는 운영 코드에서 더 이상 호출되지 않지만 **측정을 언제든 재현할 수 있도록** 남겨뒀습니다.

### Redis 키 구조

| 키 | 타입 | TTL | 용도 |
| --- | --- | --- | --- |
| `content:like:count:{id}` | String | 10분 | 좋아요 수 캐시 |
| `content:view:delta:{id}` | String | 없음 | 조회수 증분 (스케줄러가 `GETDEL`로 수거) |
| `ranking:content:daily:{yyyyMMdd}` | Sorted Set | 2일 | 일간 랭킹 |
| `ranking:content:all` | Sorted Set | 없음 | 전체 기간 랭킹 |
| `ranking:content:*:rebuilt` | String | 60초 | 재구축 쿨다운 표식 |

---

## 이 프로젝트를 관통하는 세 가지 원칙

### ① 읽고-쓰는 사이에 틈을 만들지 않는다

| 영역 | 문제 있는 코드 | 해결 |
| --- | --- | --- |
| DB 카운터 | `findById` → `increaseLikeCount()` | `UPDATE SET x = x + 1` |
| 중복 검사 | `existsBy` → `save` | DB 유니크 제약 |
| Java 카운터 | `count++` | `AtomicInteger.incrementAndGet()` |
| Java 맵 | `put(k, getOrDefault(k,0)+1)` | `merge(k, 1, Integer::sum)` |
| Redis 수거 | `GET` → `DEL` | `GETDEL` |
| Redis 교체 | `DEL` → `ZADD` | 임시 키 + `RENAME` |

**전부 같은 패턴입니다.** 세 단계를 한 단계로 묶으면 틈이 사라집니다.

### ② 고장은 시끄러울수록 좋다

| 지점 | 어떻게 |
| --- | --- |
| `@Version` | 조용한 유실 90건 → **명시적 예외 90건** |
| `ddl-auto: validate` (운영) | 스키마 불일치 시 **기동 실패** |
| `application-prod.yml` | 환경변수 누락 시 **기동 실패** |
| `@Tag`로 측정 테스트 분리 | 빨간불이 **항상 진짜 실패**를 뜻하게 |
| `/actuator/info` | **배포 시각을 응답에 노출** |

"조용한 실패"가 이 프로젝트에서 네 번 등장했습니다 — 데이터(좋아요 유실), 배포(재시작 누락), 설계(재구축 무한 반복), 환경(테스트가 개발 DB 파괴). **층위는 다르지만 "실패했는데 성공처럼 보인다"는 구조가 같았고, 해결 방향도 같았습니다.**

### ③ 요구사항이 다르면 설계도 다르다

좋아요와 조회수는 둘 다 카운터지만 전혀 다르게 구현했고, 랭킹과 캐시는 둘 다 Redis지만 무효화 전략이 다릅니다.

**같은 도구를 쓴다고 같은 방식일 이유는 없습니다.** 먼저 정할 것은 **"이 데이터는 얼마나 정확해야 하는가", "얼마나 잃어도 되는가"** 입니다.

## 함께 보기

- [db-metadata-generator](https://github.com/kang1597/db-metadata-generator) — DB 메타데이터로 테이블 정의서·VO 자동 생성
- [포트폴리오](https://github.com/kang1597/portfolio)
