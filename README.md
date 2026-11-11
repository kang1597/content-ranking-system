# 프로젝트 2 — 9단계: 테스트 격리 / CI / 설정 외부화 / README

> **기간**: 2026-11-10 ~ 2026-11-11
> **저장소**: https://github.com/kang1597/content-ranking-system
> **진행도**: **프로젝트 2 완료 (100%)**

---

## 1. 한 일

| 구간 | 내용 |
| --- | --- |
| **9-1** | 테스트 전용 MySQL(3309, tmpfs) + Redis 논리 DB 1번으로 격리 |
| **9-2** | GitHub Actions CI — service container, 측정 테스트 분리 |
| **9-3** | 설정 외부화(`${ENV:기본값}`, `.env`, 운영 프로파일) + Actuator |
| **9-4** | README 작성 |

---

# 2. 9-1 — 테스트 환경 격리

## 2-1. 문제 발견

```sql
SELECT TABLE_NAME, AUTO_INCREMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'rankingdb';
```

| 테이블 | AUTO_INCREMENT | 실제 행 수 | **사라진 것** |
| --- | --- | --- | --- |
| `member` | 1218 | 4 | **1,214** |
| `content_like` | 2089 | 7 | **2,082** |
| `content` | 46 | 15 | 31 |

처음엔 "테스트 데이터가 **누적**됐다"고 생각했는데, 실제로는 **"쌓였다가 전부 지워지기를 반복"** 하고 있었습니다.

```java
@AfterEach
void tearDown() {
    contentLikeRepository.deleteAllInBatch();
    contentRepository.deleteAllInBatch();
    memberRepository.deleteAllInBatch();   // ← 개발 DB 의 모든 회원을 지움
}
```

> **누적보다 파괴가 훨씬 심각합니다.**
>
> | | 누적 | **파괴** |
> | --- | --- | --- |
> | 증상 | 데이터가 지저분 | **수동으로 만든 데이터가 사라짐** |
> | 발견 | `COUNT`를 보면 바로 | **다음에 쓰려고 보면 없음** |
>
> 8단계에서 `api-test.http`의 `contentId`를 변수로 캡처하도록 바꾼 이유가 "`AUTO_INCREMENT`가 계속 올라가서"였는데, **올라간 이유가 바로 이것**이었습니다. 두 현상이 같은 원인인데 그때는 ID 쪽만 보고 넘어갔습니다.

### `AUTO_INCREMENT`가 되돌아가지 않는 이유

`DELETE`로 행을 지워도 카운터는 줄지 않습니다. InnoDB가 메모리에 들고 있다가 **증가만** 시키기 때문입니다.

> **왜 되돌리지 않는가**: 동시에 여러 트랜잭션이 INSERT하는데 하나가 롤백됐다고 번호를 재사용하면, 이미 그 번호를 받아간 다른 트랜잭션과 **충돌**합니다. **번호를 버리는 게 안전**합니다.
>
> 되돌리려면 `TRUNCATE TABLE`을 쓰거나 `ALTER TABLE ... AUTO_INCREMENT = 1`로 명시해야 합니다.
>
> 이 성질 덕분에 **지워진 데이터의 흔적이 남아** 문제를 숫자로 증명할 수 있었습니다.

---

## 2-2. ⭐ 이론 — 테스트 격리의 네 가지 수준

테스트가 신뢰할 만하려면 **"어떤 순서로 몇 번 돌려도 같은 결과"** 가 나와야 합니다.

| 수준 | 방법 | 격리 범위 |
| --- | --- | --- |
| 1 | `@Transactional` 롤백 | 테스트 간 ✅ / **동시성 테스트 ❌** |
| 2 | `@AfterEach` 수동 삭제 | 테스트 간 ✅ / **다른 프로세스와 ❌** |
| **3** | **전용 DB 인스턴스** | **프로세스 간 ✅** ← 채택 |
| 4 | 테스트마다 컨테이너 생성 (Testcontainers) | 완전 격리 / 느림 |

우리는 **2번**에 머물러 있었습니다. 지우긴 하는데 **지우는 대상이 개발 DB**라는 게 문제였습니다.

> **1번을 못 쓰는 이유**(5단계에서 정리):
>
> 트랜잭션은 **`ThreadLocal`에 묶여 있습니다.** 테스트 스레드가 연 트랜잭션은 작업 스레드에 전달되지 않고, 커밋 전이라 다른 트랜잭션에서 보이지도 않습니다. 동시성 테스트에서는 **실제로 커밋해야** 합니다.

---

## 2-3. ⭐⭐ 왜 H2를 쓰면 안 되는가

테스트 DB 하면 흔히 **H2 인메모리**를 떠올립니다. 빠르고 의존성 한 줄이면 끝입니다.

**이 프로젝트에서는 쓰면 안 됩니다.**

| | H2 | 실제 MySQL |
| --- | --- | --- |
| 속도 | 매우 빠름 | 느림 |
| **InnoDB 락 동작** | **재현 불가** | ✅ |
| **MVCC 스냅샷 읽기** | **다름** | ✅ |
| **FK 공유 락** | **없음** | ✅ |
| **데드락 감지** | **다름** | ✅ |

> **이 프로젝트의 핵심 산출물이 전부 InnoDB의 구체적 동작에 의존합니다.**
>
> - 5단계의 **데드락 80건**은 INSERT가 FK 무결성 검사를 위해 부모 행에 **공유 락(S)** 을 거는 InnoDB 고유 동작 때문이었습니다. H2는 FK에 그런 락을 걸지 않습니다.
> - 6단계에서 **비관적 락이 갱신 손실을 막는 이유**는 REPEATABLE READ의 MVCC 스냅샷 읽기를 `FOR UPDATE`가 우회하기 때문입니다. H2의 MVCC 구현은 다릅니다.
> - `innodb_lock_wait_timeout`, 데드락 감지 알고리즘, 락 승격 규칙 — 전부 **구현 세부사항**입니다.
>
> **H2에서 테스트를 통과시키면 "H2에서는 동작한다"는 것만 증명됩니다.** 측정 결과의 근거가 통째로 사라집니다.
>
> 일반적인 CRUD 테스트라면 H2로 충분합니다. 하지만 **"동시성 제어를 비교한다"가 프로젝트의 정체성**인 이상, 테스트 환경은 **운영과 같은 DB 엔진**이어야 합니다.
>
> → **"테스트는 운영과 가까울수록 좋다"** 는 원칙의 구체적 사례.

---

## 2-4. ⭐ tmpfs — 메모리 파일시스템

```yaml
  mysql:
    volumes:
      - mysql_data:/var/lib/mysql    # 개발용 — 디스크에 영속

  mysql-test:
    tmpfs:
      - /var/lib/mysql               # 테스트용 — 메모리에만
```

**tmpfs**는 리눅스의 **메모리 기반 파일시스템**입니다. 그 경로에 쓴 데이터가 디스크가 아니라 **RAM에 올라갑니다.**

| | 영속 볼륨 | tmpfs |
| --- | --- | --- |
| 저장 위치 | 디스크 | **RAM** |
| 컨테이너 재시작 | 데이터 유지 | **전부 사라짐** |
| 속도 | 디스크 I/O | **수십 배 빠름** |
| 정리 | `down -v` 필요 | **자동** |

> **왜 테스트 DB에 완벽한가**
>
> ① **매번 깨끗한 상태로 시작** — 이전 실행의 찌꺼기가 없습니다.
> ② **`fsync`가 메모리 쓰기** — MySQL은 커밋할 때 redo log를 디스크에 `fsync`하는데, tmpfs면 그게 메모리 복사입니다. **커밋이 훨씬 빨라집니다.**
> ③ **잃을 것이 없음** — 데이터가 사라져도 테스트 데이터일 뿐입니다.
>
> 대가는 **메모리 사용**입니다. MySQL 하나에 수백 MB를 더 씁니다.
>
> 리눅스에서 `/tmp`가 보통 tmpfs이고, `/dev/shm`도 그렇습니다. "재부팅하면 `/tmp`가 비는" 이유가 이것입니다.

---

## 2-5. ⭐ Spring 프로파일 — 정확히 어떻게 동작하는가

여기가 가장 헷갈리는 부분이라 자세히 정리합니다.

### 파일은 "교체"가 아니라 "병합"된다

```
application.yml           ← 항상 로드 (기본)
application-test.yml      ← "test" 프로파일이 활성일 때 추가 로드
```

**둘 다 읽고, 같은 키는 나중 것이 이깁니다.**

```yaml
# application.yml
server:
  port: 8081
spring:
  datasource:
    url: jdbc:mysql://localhost:3308/rankingdb
  jpa:
    hibernate:
      ddl-auto: update
```

```yaml
# application-test.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3309/rankingdb_test   # ← 이것만 덮어씀
  jpa:
    hibernate:
      ddl-auto: create-drop                            # ← 이것만 덮어씀
```

**결과**

| 키 | 최종값 | 출처 |
| --- | --- | --- |
| `server.port` | 8081 | `application.yml` (그대로 살아 있음) |
| `spring.datasource.url` | **3309** | `application-test.yml` |
| `spring.jpa.hibernate.ddl-auto` | **create-drop** | `application-test.yml` |

> **"test 프로파일을 켜면 application.yml이 무시된다"가 아닙니다.** 기본 설정 위에 **덮어쓰기**를 얹는 구조입니다. 그래서 테스트 전용 파일에는 **바꿀 것만** 적으면 됩니다.

### `src/test/resources/`에 둔 이유

```
src/main/resources/application.yml        ← 운영·개발·테스트 전부에서 보임
src/test/resources/application-test.yml   ← 테스트 클래스패스에서만 보임
```

테스트를 실행하면 **두 디렉터리가 모두 클래스패스에 올라갑니다.** 그래서 `application.yml`(main)과 `application-test.yml`(test)이 함께 읽힙니다.

반대로 **`bootJar`로 패키징할 때는 `src/test/resources/`가 포함되지 않습니다.** 테스트 설정이 운영 jar에 섞여 들어갈 일이 없습니다.

### 프로파일을 켜는 방법

| 방법 | 범위 |
| --- | --- |
| `@ActiveProfiles("test")` | **테스트 클래스별** |
| `spring.profiles.active=test` (yml) | 애플리케이션 전체 |
| `SPRING_PROFILES_ACTIVE=test` (환경변수) | 실행 환경 |
| `--spring.profiles.active=test` (CLI) | 그 실행만 |

### `@Profile("!test")` — 빈을 조건부로 등록

```java
@Configuration
@EnableScheduling
@Profile("!test")          // test 프로파일이 "아닐 때만" 등록
public class SchedulingConfig { }
```

> **`!`는 부정**입니다. 다른 표기도 가능합니다.
>
> | 표기 | 의미 |
> | --- | --- |
> | `@Profile("prod")` | prod일 때만 |
> | `@Profile("!test")` | **test가 아닐 때만** |
> | `@Profile({"dev", "local"})` | dev **또는** local |
> | `@Profile("prod & !cloud")` | prod이면서 cloud가 아닐 때 |
>
> **스케줄러를 끈 이유**: `ViewCountSyncScheduler`가 1분마다 Redis를 `SCAN`하고 `content` 테이블에 벌크 UPDATE를 날립니다. 동시성 테스트가 돌아가는 중에 끼어들면 **락 경합이 추가되고 측정 시간에 섞입니다.**
>
> **측정 조건을 고정하는 것**이 이 프로젝트의 일관된 원칙입니다. 0단계에서 `open-in-view: false`와 `rewriteBatchedStatements`를 미리 정한 것과 같은 맥락입니다.

### `ddl-auto: create-drop`

| 값 | 테스트에서의 의미 |
| --- | --- |
| `update` | **이전 실행의 스키마가 남음** → `@Version` NULL 같은 문제 재현 |
| **`create-drop`** | **매번 새 스키마.** 컨텍스트 종료 시 drop |
| `create` | 시작 시 재생성, 종료 후 데이터는 남아 **디버깅에 유리** |

6단계에서 `@Version` 때문에 `docker compose down -v`를 했던 일이 **테스트에서는 자동으로 해결**됩니다.

---

## 2-6. ⭐ Redis 논리 DB

```yaml
spring:
  data:
    redis:
      database: 1     # 개발용 0번과 분리
```

> **Redis는 기본적으로 16개의 논리 DB(0~15번)를 가집니다.**
>
> ```
> redis-cli -n 0    ← 개발용 (기본)
> redis-cli -n 1    ← 테스트용
> ```
>
> 같은 Redis 인스턴스 안에서 **키스페이스가 완전히 분리**됩니다. `SELECT 1`로 전환하고, `FLUSHDB`는 **현재 DB만** 비웁니다. **별도 컨테이너 없이 격리**할 수 있어 메모리를 아낍니다.
>
> **다만 운영에서는 쓰지 않습니다.**
>
> | 이유 | 설명 |
> | --- | --- |
> | **클러스터 모드 미지원** | Redis Cluster는 **DB 0번만** 사용 가능 |
> | 자원 공유 | 논리적 분리일 뿐, **메모리·CPU·단일 스레드를 공유** |
> | 운영 실수 | `FLUSHALL`은 **모든 DB**를 날림 |
>
> 운영에서 테넌트를 나눌 땐 **키 prefix**(`tenant1:...`)나 **별도 인스턴스**를 씁니다. 논리 DB는 "개발 편의 기능"에 가깝습니다.

### `flushDb()` vs `flushAll()`

```java
redisTemplate.execute((RedisCallback<Object>) connection -> {
    connection.serverCommands().flushDb();   // 현재 DB(1번)만
    return null;
});
```

**`flushAll()`을 쓰면 개발용 0번까지 전멸합니다.** 한 글자 차이로 결과가 크게 다른 지점입니다.

> **`RedisCallback`으로 감싸는 이유**: `flushDb`는 키 조작이 아니라 **서버 명령**이라 `opsForValue()` 같은 고수준 API에 없습니다. **저수준 커넥션을 직접** 꺼내야 합니다.
>
> ```java
> connection.keyCommands()     // DEL, EXPIRE, SCAN ...
> connection.stringCommands()  // GET, SET, INCR ...
> connection.zSetCommands()    // ZADD, ZINCRBY ...
> connection.serverCommands()  // FLUSHDB, INFO, DBSIZE ...
> ```
>
> Spring Data Redis 2.0부터 **명령 종류별로 인터페이스를 분리**했습니다. Redis 명령이 수백 개라 하나의 거대 인터페이스로는 감당이 안 되기 때문입니다.

---

# 3. 9-2 — GitHub Actions CI

## 3-1. ⭐ CI가 무엇이고 왜 필요한가

> **CI(Continuous Integration, 지속적 통합)** 는 원래 **"여러 명이 작업한 코드를 자주 합친다"** 는 뜻이었습니다. 통합을 미루면 나중에 한꺼번에 터지니 **작게 자주 합쳐 문제를 빨리 발견하자**는 발상입니다.
>
> 거기에 **"합칠 때마다 자동으로 검증한다"** 가 붙으면서 지금의 CI가 됐습니다.
>
> **핵심은 "내 PC에서는 되는데요"를 없애는 것입니다.**
>
> | | 로컬 테스트 | CI |
> | --- | --- | --- |
> | 환경 | 내 PC (설정이 누적됨) | **매번 깨끗한 상태** |
> | 실행 | 사람이 기억해야 | **자동** |
> | 결과 | 나만 봄 | **저장소에 공개** |
>
> **"매번 깨끗한 상태"가 가장 큰 가치**입니다. 내 PC에는 어제 설치한 것, 켜둔 컨테이너, 수정했다 잊은 설정이 쌓여 있습니다. CI는 **"저장소에 있는 것만으로 돌아가는가"** 를 증명합니다.
>
> 9-1에서 테스트 DB를 분리한 게 **선행 조건**이었습니다. 개발 DB에 의존하는 테스트는 CI에서 절대 돌 수 없습니다.

**CD(Continuous Delivery/Deployment)** 는 그다음 단계입니다 — 검증을 통과하면 자동으로 배포까지. 이 프로젝트는 배포 대상이 없어 CI까지만 합니다.

## 3-2. GitHub Actions 구조

```
workflow  (.github/workflows/ci.yml 파일 하나)
   │
   ├─ on:     언제 실행할지 (push, pull_request, schedule, workflow_dispatch...)
   │
   └─ jobs:   병렬로 도는 작업 단위
        │
        └─ job: test
             ├─ runs-on:  어떤 머신에서 (ubuntu-latest)
             ├─ services: 함께 띄울 컨테이너
             └─ steps:    순서대로 실행할 단계
                  ├─ uses: 남이 만든 액션 재사용
                  └─ run:  셸 명령 직접 실행
```

| 개념 | 설명 |
| --- | --- |
| **Runner** | 작업이 도는 가상 머신. GitHub이 제공(공개 저장소 무료) |
| **Action** | 재사용 가능한 작업 단위. `actions/checkout@v5` 같은 것 |
| **Step** | 명령 하나. **`uses` 또는 `run` 중 하나가 반드시 필요** |

> **`@v5` 같은 버전 고정**은 `mysql:8.0`을 쓴 것과 같은 이유입니다 — **재현 가능성**. `@main`을 쓰면 어제 되던 게 오늘 깨질 수 있습니다.

## 3-3. ⭐ Service Container — 왜 docker-compose가 아닌가

```yaml
services:
  mysql:
    image: mysql:8.0
    ports:
      - 3309:3306
    options: >-
      --health-cmd="mysqladmin ping -h localhost -uroot -proot1234"
      --health-interval=10s
```

| | `docker compose up` (step) | **`services:`** |
| --- | --- | --- |
| 기동 시점 | steps 중간 | **job 시작 전** |
| 준비 대기 | **직접 sleep/polling** 작성 | **헬스체크 통과까지 자동 대기** |
| 정리 | 직접 `down` | **자동** |
| 네트워크 | compose 네트워크 | **runner의 localhost에 포트 매핑** |

> **"헬스체크 통과까지 자동으로 기다린다"가 핵심입니다.**
>
> `docker compose up -d`는 컨테이너를 **시작만** 하고 바로 반환합니다. MySQL 초기화에 20~30초가 걸리는데 그 사이에 테스트가 시작되면 `Connection refused`로 실패합니다 — 0단계에서 겪었던 그 문제입니다.
>
> `services:`를 쓰면 GitHub이 **`healthy`가 될 때까지 steps를 시작하지 않습니다.**
>
> **포트를 3309로 맞춘 이유**: `application-test.yml`을 **한 글자도 안 고치고** CI에서 그대로 씁니다. 환경변수로 덮어쓰는 방법도 있지만, **로컬과 CI가 같은 설정을 쓰는 쪽**이 "CI에서만 되는/안 되는" 상황을 줄입니다.

### 한계 — `command`를 못 준다

```yaml
# 로컬 compose 에서는 가능
command:
  - --character-set-server=utf8mb4
  - --collation-server=utf8mb4_unicode_ci
```

`services:`의 `options:`는 **`docker run` 옵션만** 받습니다. 컨테이너 `command`는 바꿀 수 없습니다.

**다행히 MySQL 8의 기본 문자셋이 이미 `utf8mb4`** 라 한글은 문제없습니다. collation만 `utf8mb4_0900_ai_ci`(로컬은 `utf8mb4_unicode_ci`)로 다른데, **둘 다 대소문자 무시**라 테스트 결과에는 영향이 없습니다.

## 3-4. YAML 폴디드 스칼라 `>-`

```yaml
options: >-
  --health-cmd="..."
  --health-interval=10s
```
→ `--health-cmd="..." --health-interval=10s`

| 표기 | 동작 |
| --- | --- |
| `>` | 여러 줄을 **공백으로 이어** 한 줄로, 끝에 개행 1개 |
| **`>-`** | 위와 같되 **끝 개행 제거** |
| `\|` | 줄바꿈을 **그대로 유지** |
| `\|-` | 위와 같되 끝 개행 제거 |

`docker run`에 넘어가는 **옵션 문자열이라 한 줄이어야 하는데**, 가독성을 위해 쪼개 쓴 것입니다.

## 3-5. ⭐ 실행 권한 비트 — Windows의 함정

```
/home/runner/work/_temp/xxx.sh: line 1: ./gradlew: Permission denied
Error: Process completed with exit code 126
```

> **Windows에는 유닉스 실행 권한 개념이 없습니다.** NTFS는 ACL로 권한을 관리하지, `rwxr-xr-x` 같은 비트가 없습니다.
>
> 그래서 Windows에서 `gradlew`를 커밋하면 Git에 **실행 비트 없는 상태(`100644`)** 로 저장되고, 우분투 러너에서 실행이 거부됩니다.
>
> **종료 코드 126**은 유닉스 관례로 **"명령을 찾았으나 실행할 수 없음"** 을 뜻합니다(127은 "명령을 찾을 수 없음").

**근본 해결** — Git 인덱스에 실행 비트를 박아둡니다.

```powershell
git update-index --chmod=+x gradlew
git ls-files -s gradlew
# 100755 abc123... 0  gradlew     ← 755 면 성공
```

파일 내용은 그대로인데 **모드가 `100644` → `100755`로** 바뀝니다.

> workflow의 `chmod +x` 줄은 **남겨두는 게 안전**합니다. 다른 사람이 Windows에서 다시 커밋하면 또 풀릴 수 있거든요. **멱등한 방어**입니다.

## 3-6. ⭐ JUnit `@Tag`와 Gradle 태스크 분리

| | `LikeServiceConcurrencyTest` | `LikeStrategyComparisonTest` |
| --- | --- | --- |
| 목적 | **검증** (100이어야 한다) | **측정** (비교표 출력) |
| 결과 | 결정적 — 항상 통과해야 | **비결정적** |
| 시간 | ~1분 | ~5분 |
| CI | ✅ | ❌ **제외** |

> **⭐ 비결정적인 테스트를 CI에 넣으면 안 됩니다.**
>
> 가끔 실패하는 테스트(**flaky test**)가 있으면 사람들이 **빨간불을 무시하기 시작**합니다. "아 또 그거네, 재실행하면 되겠지." 그러면 **진짜 실패도 묻힙니다.**
>
> **깨진 창문 이론**과 같습니다 — 창문 하나가 깨진 채 방치되면 나머지도 금방 깨집니다. **CI의 빨간불은 항상 "진짜 문제"를 뜻해야** 신호로서 가치가 있습니다.
>
> 측정은 **필요할 때 손으로** 돌리는 게 맞습니다.

```java
@Tag("measurement")
class LikeStrategyComparisonTest { }
```

```groovy
tasks.named('test') {
    useJUnitPlatform {
        excludeTags 'measurement'
    }
}

tasks.register('measurementTest', Test) {
    group = 'verification'
    description = '동시성 제어 전략 성능 비교 (수동 실행용)'
    testClassesDirs = sourceSets.test.output.classesDirs
    classpath = sourceSets.test.runtimeClasspath
    useJUnitPlatform {
        includeTags 'measurement'
    }
}
```

> **`@Tag`는 테스트를 분류하는 라벨**입니다. `@Tag("slow")`, `@Tag("integration")` 처럼 자유롭게 붙이고 실행 시 필터링합니다.
>
> **`tasks.register('measurementTest', Test)`** 는 **새 Gradle 태스크를 만드는 것**입니다. `Test` 타입이라 테스트 실행 기능을 그대로 물려받고, 어느 클래스를 어떤 클래스패스로 돌릴지만 지정해주면 됩니다.
>
> | 속성 | 의미 |
> | --- | --- |
> | `testClassesDirs` | **컴파일된 테스트 클래스가 있는 디렉터리** |
> | `classpath` | 실행에 필요한 의존성 전부 |
> | `group` | `./gradlew tasks` 목록에서의 분류 |
>
> `sourceSets.test`는 Gradle이 관리하는 **"테스트 소스 묶음"** 입니다. `src/test/java`, `src/test/resources`와 그 컴파일 결과·의존성을 모두 담고 있습니다. 직접 경로를 적지 않고 이걸 참조하면 **설정이 바뀌어도 따라갑니다.**

| 명령 | 실행 대상 |
| --- | --- |
| `./gradlew test` | measurement **제외** (CI가 쓰는 것) |
| `./gradlew measurementTest` | measurement **만** |

IntelliJ에서 클래스를 직접 실행하는 건 태그와 무관하게 그대로 됩니다.

## 3-7. Gradle 캐시와 `--no-daemon`

```yaml
- uses: actions/setup-java@v5
  with:
    java-version: '17'
    distribution: 'temurin'
    cache: gradle
```

> **`cache: gradle`** — 매번 의존성을 새로 받으면 빌드가 몇 분씩 걸립니다. `~/.gradle/caches`를 캐싱해 **두 번째 실행부터 1~2분 단축**됩니다.
>
> 캐시 키는 `build.gradle`과 `gradle-wrapper.properties`의 **해시로 자동 계산**됩니다 — 의존성이 바뀌면 자동으로 새 캐시를 만듭니다.
>
> **`--no-daemon`** — Gradle은 평소 **데몬 프로세스를 띄워 재사용**합니다(JVM 기동 비용 절감). CI는 **한 번 쓰고 버리는 환경**이라 데몬이 의미가 없고, 종료되지 않아 job이 안 끝나는 경우가 있습니다.

## 3-8. `if: always()`

```yaml
- name: 테스트 리포트 업로드
  if: always()
  uses: actions/upload-artifact@v5
```

> 기본값은 **"이전 step이 성공했을 때만"** 입니다. 이게 없으면 **정작 실패했을 때 리포트를 못 받습니다.**
>
> CI에서 가장 아쉬운 순간이 "실패했는데 왜 실패했는지 모를 때"입니다.
>
> | 표현식 | 의미 |
> | --- | --- |
> | (생략) | `success()` — 이전까지 성공했을 때 |
> | `always()` | **항상** |
> | `failure()` | 실패했을 때만 |
> | `cancelled()` | 취소됐을 때 |

---

# 4. 9-3 — 설정 외부화

## 4-1. ⭐ 왜 외부화하는가

```yaml
# 저장소에 그대로 있던 것들
password: dev1234
jwt.secret: Y29udGVudC1yYW5raW5n...
MYSQL_ROOT_PASSWORD: root1234
```

로컬 개발용이라 당장 피해는 없지만, **습관의 문제**입니다.

> **⭐ Git 히스토리는 영원합니다.**
>
> 실수로 운영 비밀번호를 커밋했다가 다음 커밋에서 지워도 **이전 커밋에 그대로 남아 있습니다.** `git log -p`로 누구나 볼 수 있죠.
>
> 완전히 지우려면 `git filter-repo`로 **히스토리 전체를 재작성**하고 강제 푸시해야 하는데, 이미 clone해 간 사람의 사본에는 그대로 남습니다. **실무에서는 "유출된 것으로 간주하고 키를 폐기·재발급"** 하는 게 원칙입니다.
>
> GitHub은 공개 저장소에서 AWS 키 같은 패턴을 감지해 경고를 보내는데, **봇들이 그보다 빨리 긁어갑니다.** 공개 저장소에 올라간 AWS 키가 **몇 분 안에** 채굴에 쓰인 사례가 많습니다.

### 12-Factor App의 Config 원칙

> **"설정을 코드에서 분리하고, 환경변수에 둔다."**
>
> 판단 기준은 간단합니다 — **"이 저장소를 지금 당장 공개해도 되는가?"**
>
> | 코드에 둬도 되는 것 | 환경변수로 빼야 하는 것 |
> | --- | --- |
> | 타임아웃, 재시도 횟수 | **비밀번호, API 키, 시크릿** |
> | 페이지 크기 상한 | 환경마다 다른 **호스트·포트** |
> | 비즈니스 규칙 | 외부 서비스 **엔드포인트** |
>
> **핵심 이점은 "하나의 빌드 산출물을 모든 환경에 배포"** 할 수 있다는 것입니다. 같은 jar를 개발·스테이징·운영에 올리고 **환경변수만 다르게** 줍니다.
>
> 환경별로 다시 빌드하면 **"스테이징에서 테스트한 것과 운영에 올라간 것이 다른"** 상황이 생깁니다.

## 4-2. ⭐ `${ENV:기본값}` 패턴과 포트폴리오의 트레이드오프

```yaml
jwt:
  secret: ${JWT_SECRET}          # 환경변수 없으면 기동 실패 — 운영 기준으로는 맞음
  secret: ${JWT_SECRET:기본값}   # 없으면 기본값 — clone 후 바로 실행 가능
```

> **포트폴리오는 "clone 후 즉시 실행"이 돼야 합니다.** README에 "환경변수 5개를 먼저 설정하세요"라고 쓰면 거기서 멈추는 사람이 생깁니다.
>
> **채택: `${ENV:로컬전용기본값}` + 운영 프로파일에서는 기본값 제거.**
>
> - `application.yml`의 기본값은 **로컬 개발 전용임을 주석으로 명시**
> - **`application-prod.yml`에서는 기본값 없이** 선언 → 환경변수가 없으면 기동 실패
>
> **"편의"와 "운영 안전"을 둘 다** 가져갑니다. 그리고 **이 판단 자체를 README에 적으면** 트레이드오프를 의식했다는 증거가 됩니다.

### 설정 우선순위 (0단계 복습, 여기서 실제로 쓰임)

```
1. application.yml (jar 내부)
2. jar 외부의 application.yml
3. OS 환경변수                      ← 운영에서 쓸 것
4. JVM 시스템 프로퍼티 (-Dkey=value)
5. 커맨드라인 인자 (--key=value)     ← 아래가 이김
```

### Relaxed Binding — 환경변수 이름 변환

| yml 키 | 환경변수 |
| --- | --- |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` |
| `jwt.expiration-millis` | `JWT_EXPIRATION_MILLIS` |
| `spring.data.redis.host` | `SPRING_DATA_REDIS_HOST` |

**규칙**: 점(`.`)과 하이픈(`-`)을 **언더스코어(`_`)** 로, **전부 대문자**로.

> 유닉스 환경변수 이름에는 점을 못 쓰기 때문에 Spring이 이 변환을 해줍니다.

### `${KEY:기본값}`에서 콜론이 여러 개일 때

```yaml
url: ${DB_URL:jdbc:mysql://localhost:3308/rankingdb?...}
```

**Spring은 첫 번째 콜론만 구분자로 봅니다.** 그 뒤는 전부 기본값이라 JDBC URL처럼 콜론이 들어간 값도 그대로 쓸 수 있습니다.

## 4-3. ⭐⭐ `.env`와 Spring 설정은 완전히 별개다

**가장 헷갈리는 부분입니다.**

```
.env  ──읽음──→  docker compose  ──→  MySQL 컨테이너의 환경변수

application.yml 의 ${DB_PASSWORD:dev1234}
   → JVM 프로세스의 OS 환경변수를 찾아봄
   → 없음
   → 기본값 dev1234 사용
```

> **Spring Boot는 `.env` 파일을 읽지 않습니다.** 둘은 **서로 모르는 사이**이고, 우연히 값이 같을 뿐입니다.
>
> `.env`를 Spring이 읽게 하려면 `spring-dotenv` 같은 라이브러리가 필요합니다.
>
> **그래서 `.env`의 비밀번호를 바꾸면 `application.yml`의 기본값도 같이 바꿔야** 둘이 맞습니다. 이 중복이 거슬리면 앱도 환경변수로 받게 하면 되는데, 그러면 "clone 후 즉시 실행"이 깨집니다.
>
> | | 누가 읽나 | 적용 대상 |
> | --- | --- | --- |
> | `.env` | **docker compose** | 컨테이너(MySQL) |
> | `${ENV:기본값}` | **Spring Boot** | 애플리케이션 |

### `.env.example` 패턴

```
.env          ← 실제 값, .gitignore 로 제외
.env.example  ← 키 목록만, 커밋함
```

> **"비밀은 숨기되, 비밀의 목록은 공개한다"** 가 원칙입니다. 목록까지 숨기면 **아무도 실행할 수 없습니다.**
>
> clone한 사람은 `cp .env.example .env` 후 값만 채우면 됩니다.

### `.env` 문법

```
MYSQL_DATABASE=rankingdb
```

- **YAML이 아닙니다** — 콜론이 아니라 **등호**
- **들여쓰기 없음** — 계층 개념이 없는 평평한 목록
- 따옴표는 보통 안 씀 (쓰면 따옴표까지 값에 포함되는 경우가 있음)

**Docker Compose는 같은 디렉터리의 `.env`를 자동으로 읽습니다.** 별도 설정이 필요 없습니다.

```powershell
docker compose config    # 치환 결과 미리 확인
```

## 4-4. ⭐ `$$` 이스케이프와 `CMD` vs `CMD-SHELL`

```yaml
healthcheck:
  test: ["CMD-SHELL", "mysqladmin ping -h localhost -uroot -p$$MYSQL_ROOT_PASSWORD"]
```

### 왜 `CMD`가 아니라 `CMD-SHELL`인가

| 형식 | 실행 방법 | 변수 치환 |
| --- | --- | --- |
| `["CMD", "a", "b"]` | **셸 없이 직접 실행** | ❌ 안 됨 |
| `["CMD-SHELL", "a b"]` | `/bin/sh -c "a b"` | ✅ **셸이 치환** |

`CMD`로 쓰면 `$MYSQL_ROOT_PASSWORD`가 **문자열 그대로** 비밀번호가 되어버립니다.

### 왜 `$$`인가

```
$MYSQL_ROOT_PASSWORD   → compose 가 .env 에서 찾아 치환 (여기선 틀림)
$$MYSQL_ROOT_PASSWORD  → 컨테이너 셸이 자기 환경변수에서 치환 (맞음)
```

> **`$$`는 "이건 내 것이 아니라 컨테이너 안에서 풀어라"는 이스케이프**입니다.
>
> 컨테이너 안에는 `MYSQL_ROOT_PASSWORD`가 환경변수로 들어가 있으니(`environment:` 블록), 거기서 읽는 게 맞습니다.
>
> **`docker compose config` 출력에는 `$$`가 그대로 보입니다.** 그 출력이 **"다시 compose에 먹여도 되는 파일"** 이어야 하기 때문입니다. `$` 하나로 출력하면 재실행 시 compose가 변수로 해석해버립니다.
>
> **진짜 확인은 `docker compose ps`의 `healthy`** 뿐입니다.

## 4-5. 운영 프로파일 — 왜 이렇게 설정하나

```yaml
# application-prod.yml
spring:
  datasource:
    url: ${DB_URL}            # 기본값 없음
  jpa:
    hibernate:
      ddl-auto: validate
logging:
  level:
    org.hibernate.SQL: off
```

| 설정 | 운영값 | 이유 |
| --- | --- | --- |
| **기본값 없음** | — | **환경변수 누락을 기동 시점에 잡음** |
| `ddl-auto` | **`validate`** | 엔티티와 테이블이 다르면 **기동 실패**. DDL은 절대 실행 안 함 |
| `org.hibernate.SQL` | **`off`** | **성능 + 개인정보** |

> **⭐ SQL 로깅을 운영에서 끄는 두 가지 이유**
>
> **① 성능** — 모든 쿼리를 문자열로 만들어 I/O에 씁니다. 초당 수천 건이면 그 자체가 병목입니다.
>
> **② 개인정보** — `org.hibernate.orm.jdbc.bind: trace`는 **바인딩된 실제 값**을 찍습니다.
> ```
> binding parameter (1:VARCHAR) <- [hong@test.com]
> binding parameter (2:VARCHAR) <- [$2a$10$N9qo8uLO...]
> ```
> **이메일과 비밀번호 해시가 로그 파일에 평문으로 남습니다.** 로그는 보통 중앙 수집기로 보내지고 여러 사람이 보므로 **개인정보 유출 경로**가 됩니다.

> **`ddl-auto: validate`가 운영의 정답인 이유**
>
> | 값 | 운영에서의 위험 |
> | --- | --- |
> | `update` | **예기치 않은 DDL**. 타입 변경·삭제는 못 하면서 **실패는 조용히 삼킴** |
> | `create` | 테이블을 드롭하고 재생성 — **데이터 전멸** |
> | **`validate`** | **DDL 안 함.** 스키마 불일치 시 기동 실패 |
> | `none` | 검증도 안 함 |
>
> `validate`는 **"스키마가 코드와 맞는지 확인하고, 틀리면 뜨지 않는다"** 입니다. **잘못된 버전이 배포되는 걸 기동 시점에 막습니다.**
>
> 실제 스키마 변경은 **Flyway/Liquibase** 가 담당합니다. 이 프로젝트에서 `ddl-auto: update`에 **세 번**(tinytext / @Version NULL / NOT NULL 컬럼 추가) 당한 경험이 그 근거입니다.

---

# 5. 9-3 후반 — Actuator

## 5-1. Actuator가 무엇인가

> **Spring Boot Actuator는 "애플리케이션의 내부 상태를 HTTP로 들여다보는 창"** 입니다. 운영 중인 앱에 대해 이런 걸 물어볼 수 있습니다.
>
> | 엔드포인트 | 보여주는 것 |
> | --- | --- |
> | `/actuator/health` | DB·Redis·디스크 연결 상태 |
> | `/actuator/info` | 빌드 버전·시각 |
> | `/actuator/metrics` | JVM 메모리, GC, 요청 수 |
> | `/actuator/env` | **모든 설정값** ⚠️ |
> | `/actuator/beans` | 등록된 빈 전체 |
> | `/actuator/heapdump` | **힙 덤프 파일** ⚠️ |
>
> 직접 만들려면 상당한 코드인데, **의존성 한 줄로 끝납니다.**

## 5-2. ⭐ 화이트리스트 방식

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

> **`/actuator/env`는 비밀번호까지 노출합니다.** `/actuator/heapdump`는 메모리 전체를 파일로 내려줍니다 — 거기엔 토큰, 세션, 평문 데이터가 들어 있습니다.
>
> 그래서 Spring Boot는 기본적으로 **`health`만** 열어둡니다. **필요한 것만 명시적으로 추가**하는 화이트리스트 방식입니다.
>
> `include: "*"` 를 쓰는 예제가 많은데 **운영에서는 절대 안 됩니다.**

## 5-3. `show-details`를 프로파일별로

| | 로컬(`always`) | 운영(`never`) |
| --- | --- | --- |
| 응답 | DB·Redis 연결 상태, 디스크 여유까지 | `{"status":"UP"}` 만 |
| 이유 | 디버깅에 유용 | **내부 구조 노출 방지** |

운영에서 상세가 필요하면 `when-authorized` + 역할 기반 인가를 씁니다.

## 5-4. `buildInfo()` — 8단계 숙제 해결

```groovy
springBoot {
    buildInfo()
}
```

빌드할 때마다 `META-INF/build-info.properties`를 생성합니다.

```properties
build.artifact=content-ranking-system
build.version=0.0.1-SNAPSHOT
build.time=2026-11-11T07:10:51.763Z
```

```json
GET /actuator/info
{
  "build": {
    "artifact": "content-ranking-system",
    "time": "2026-11-11T07:10:51.763Z",
    "version": "0.0.1-SNAPSHOT",
    "group": "com.portfolio"
  }
}
```

> **8단계에서 "재시작을 안 해서 옛 코드가 돌고 있었다"를 알아챌 방법이 없었습니다.** `POST /api/rankings/rebuild`가 404를 내는 걸 보고서야 눈치챘죠.
>
> 이제 **`build.time` 한 줄로 1초 만에** 확인합니다. 이 값이 내가 빌드한 시각과 다르면 **옛 프로세스**입니다.
>
> **`Z`는 UTC**입니다(`07:10:51Z` = 한국시간 16:10:51). 서버가 여러 타임존에 있어도 **같은 값으로 비교**할 수 있도록 빌드 메타데이터는 환경 독립적으로 기록합니다.

## 5-5. ⭐⭐ Liveness vs Readiness

```json
"groups": [ "liveness", "readiness" ]
```

| 엔드포인트 | 포함되는 것 | 실패하면 |
| --- | --- | --- |
| `/actuator/health` | **전부** (db, redis, disk, ssl...) | 사람이 보는 종합 상태 |
| `/actuator/health/liveness` | `livenessState` **만** | **프로세스 재시작** |
| `/actuator/health/readiness` | `readinessState` **만** | **트래픽에서 제외** |

> **둘의 차이가 핵심입니다.**
>
> | | Liveness | Readiness |
> | --- | --- | --- |
> | 질문 | **"프로세스가 살아 있나?"** | **"지금 요청을 받아도 되나?"** |
> | 실패 시 조치 | **죽이고 다시 띄움** | **로드밸런서에서 뺌** (프로세스는 유지) |
> | 회복 | 재시작으로만 | 상태가 좋아지면 **자동 복귀** |
>
> **Liveness에 외부 의존성을 넣으면 재앙입니다.** DB가 잠깐 끊겼다고 모든 앱 인스턴스를 재시작하면, **DB가 돌아왔을 때 전부 동시에 재기동**하면서 커넥션 폭풍이 일어납니다. **장애가 증폭됩니다.**
>
> 그래서 **기본 liveness 그룹에는 `livenessState`만** 들어 있습니다 — "JVM이 복구 불가능한 상태인가"만 봅니다.
>
> 쿠버네티스에 올린다면 프로브를 **`/actuator/health`가 아니라 그룹 엔드포인트**에 걸어야 합니다.
> ```yaml
> livenessProbe:  { httpGet: { path: /actuator/health/liveness } }
> readinessProbe: { httpGet: { path: /actuator/health/readiness } }
> ```

### 우리 설계와의 충돌

`/actuator/health`는 클래스패스를 보고 **자동으로 `RedisHealthIndicator`를 등록**합니다(AutoConfiguration). 그런데 우리는 "Redis가 죽어도 DB로 동작한다"(Graceful Degradation)로 설계했는데, 헬스체크가 `DOWN`을 내보내면 **로드밸런서가 멀쩡한 인스턴스를 빼버립니다.**

**그룹을 쓰면 해결됩니다** — Redis는 `/actuator/health`에만 영향을 주고, `readiness`에는 들어가지 않습니다.

---

# 6. 트러블슈팅

## ① 테스트가 개발 DB를 파괴하고 있었다 ⭐

(위 2-1 참조)

**증거**: `AUTO_INCREMENT` 1218/2089 vs 실제 행 4/7

**해결**: 테스트 전용 MySQL(3309, tmpfs) + Redis DB 1번

## ② `RedisCallback` import 경로

```java
import org.springframework.data.redis.connection.RedisCallback;   // ❌ 없음
import org.springframework.data.redis.core.RedisCallback;         // ✅
```

**두 에러가 하나의 원인이었습니다.**

```
심볼 'RedisCallback'을(를) 해결할 수 없습니다
모호한 메서드 호출입니다 — execute(RedisCallback) / execute(SessionCallback) 둘 다 일치
```

> `RedisCallback`을 못 찾으니 `(RedisCallback<Object>)` 캐스팅이 무효가 되고, 그러면 컴파일러가 람다를 어느 오버로드에 맞춰야 할지 모릅니다.
>
> ```java
> execute(RedisCallback<T>)    // 커넥션 하나로 저수준 명령
> execute(SessionCallback<T>)  // MULTI/EXEC 트랜잭션용
> ```
>
> 둘 다 **함수형 인터페이스에 파라미터가 하나**라서, 타입 힌트가 없으면 구분이 안 됩니다. **캐스팅이 그 힌트 역할**을 합니다.
>
> `RedisConnection`은 `connection` 패키지인데 **콜백 인터페이스는 `core`** 에 있습니다 — `RedisTemplate`과 짝을 이루는 타입이라 그렇게 둔 것으로 보입니다.

## ③ `gradlew` 실행 권한 — `exit code 126`

(위 3-5 참조) → `git update-index --chmod=+x gradlew`

## ④ CI가 숨어 있던 환경 의존성을 찾아냈다 ⭐⭐

```
3 tests completed, 1 failed
  ✅ LikeServiceConcurrencyTest (2건)
  ❌ ContentRankingSystemApplicationTests > contextLoads()
```

`@ActiveProfiles("test")`가 빠져 **개발용 DataSource(3308)에 붙으려 했습니다.**

```
BeanCreationException
 └ ServiceException
   └ HibernateException at DialectFactoryImpl.java:190
```

> **`Unable to determine Dialect`는 원인이 아니라 결과입니다** — 0단계에서 만났던 바로 그 에러.
>
> Hibernate는 직접 DB에 접속해 `DatabaseMetaData`로 제품명·버전을 읽어 Dialect를 정합니다. **연결이 안 되면 메타데이터를 못 읽는 것뿐**입니다. 괄호 안 조언대로 Dialect를 명시하면 **에러 메시지만 바뀌고 연결 실패는 그대로**입니다.

> **⭐ 이게 CI의 존재 이유입니다.**
>
> ```
> [내 PC]  3308 개발 DB 떠 있음  →  통과  →  "문제없네"
> [CI]     3308 없음             →  실패  →  "개발 DB 에 의존하고 있었네"
> ```
>
> 9-1에서 "테스트 DB를 분리했다"고 했지만 **실제로는 두 개만 분리하고 하나를 빠뜨렸습니다.** 로컬에서는 영원히 못 찾았을 겁니다.
>
> **"매번 깨끗한 상태에서 돌린다"** 가 주는 효과입니다.

**교훈**: `contextLoads()`는 비어 보이지만 **"Spring 컨텍스트 전체가 뜨는가"** 를 검증합니다. 빈 등록 누락, 순환 참조, 설정 오류를 **가장 싸게 잡아내는 테스트**입니다. 지우면 안 됩니다.

## ⑤ `Invalid workflow file` — `uses:` 줄 삭제

```
(Line 42, Col 9): There's not enough info to determine what you meant.
(Line 43): Required property is missing: uses
```

`@v4` → `@v5` 치환 과정에서 **`uses:` 세 줄이 통째로 사라졌습니다.**

> **step은 `uses`(액션) 또는 `run`(셸) 중 하나가 반드시 있어야 합니다.** `with:`는 **`uses:`에 넘기는 파라미터**라 단독으로는 의미가 없습니다 — **함수 호출 없이 인자만 적어둔 셈**입니다.
>
> `run:`이 있던 두 step은 멀쩡했고, `uses:`가 날아간 셋만 에러가 났습니다.

**YAML 들여쓰기 규칙 복습**

```yaml
# ✅ uses 가 name 과 같은 깊이 (대시 뒤 칸에 맞춤)
      - name: 소스 체크아웃
        uses: actions/checkout@v5

# ❌ 대시가 하나 더 → 별개의 step 두 개
      - name: 소스 체크아웃
      - uses: actions/checkout@v5
```

**리스트 항목에서 `- `는 공백 두 칸과 같은 역할**을 합니다.

## ⑥ 커밋 안 하고 푸시 — "고쳤는데 반영 안 됨" 세 번째 ⭐

```
git log --oneline -3
8535249 (HEAD -> main, origin/main)  fix: contextLoads 테스트에 test 프로파일 적용
```

수정이 **스테이징까지만 되고 커밋이 안 됐습니다.** 워크플로 파일이 안 바뀌었으니 GitHub 입장에서는 실행할 이유가 없었고, Actions에 아무것도 안 떴습니다.

> **이번 세션에서 세 번째입니다.**
>
> | 시점 | 무엇이 안 갔나 |
> | --- | --- |
> | 8단계 | 재시작을 안 해서 **옛 프로세스**가 응답 |
> | 9-1 | `git add`만 하고 **커밋 안 함** |
> | 9-2 | 수정 후 **커밋 안 함** |
>
> 전부 **"내가 한 변경이 실제로 반영됐는가"를 확인하지 않은 것**이 원인입니다.
>
> **습관 하나면 전부 막힙니다.**
> ```powershell
> git add . ; git status ; git log --oneline -1
> ```

## ⑦ 포트 충돌로 운영 프로파일 검증이 엉뚱하게 실패 ⭐

```
기대: DB_URL 환경변수 없음  →  Could not resolve placeholder
실제: 포트 8081 사용 중      →  Unable to start web server
```

IntelliJ의 앱을 안 끄고 `bootRun`을 돌려서, **설정 검증까지 가지도 못하고** 포트에서 막혔습니다.

> **"실패해야 정상"이라고 했는데 실제로 실패했으니 넘어갈 뻔했습니다.** 그런데 **검증하려던 것과 전혀 다른 이유로 실패**한 겁니다.
>
> 이대로 넘어갔다면 **"운영 프로파일이 환경변수를 제대로 강제한다"는 걸 증명하지 못한 채 증명했다고 믿게** 됩니다.
>
> 6단계의 **측정 지표 오류**와 같은 종류입니다 — 숫자는 나왔는데 그 숫자가 다른 걸 재고 있었죠.
>
> **"실패를 확인할 때는 실패의 이유까지 확인해야 합니다."**

앱을 끄고 다시 돌리니 정확한 에러가 나왔습니다.

```
Could not resolve placeholder 'JWT_SECRET' in value "${JWT_SECRET}" <-- "${jwt.secret}"
```

> **`"${JWT_SECRET}" <-- "${jwt.secret}"`** 이 화살표는 **해석 경로를 역순으로** 보여줍니다 — "`jwt.secret`을 찾았더니 `${JWT_SECRET}`이 나왔고, 그걸 또 찾으니 없다".
>
> `DB_URL`이 아니라 `JWT_SECRET`에서 먼저 터진 건 **빈 생성 순서** 때문입니다. Tomcat이 서블릿 필터를 등록하며 `jwtAuthenticationFilter` → `jwtProvider`를 먼저 만들었고, 거기서 멈췄습니다.

---

# 7. 검증 결과

## 테스트 격리

```
The following 1 profile is active: "test"
Database JDBC URL [jdbc:mysql://localhost:3309/rankingdb_test?...]
Hibernate: create table content (...)
```

```
================ 동시 좋아요 결과 ================
 요청 수              : 100
 성공 / 실패          : 100 / 0
 content_like 행 수   : 100
 content.like_count   : 100
 손실된 카운트        : 0
 소요 시간            : 2959 ms
================================================

============== 동시 중복 좋아요 결과 ==============
 성공 / 실패          : 1 / 99
 실패 원인            : {AlreadyLikedException=99}
 content_like 행 수   : 1
================================================
```

**DB를 바꿔도 결과가 동일합니다.** ④ DB 원자적 연산이 **환경에 의존하지 않는 해법**이라는 증거입니다.

테스트 후 개발 DB 확인:

```sql
SELECT COUNT(*) FROM member;   -- 4 (그대로)
```

```
127.0.0.1:6379> SELECT 0 → KEYS ranking:*   → 개발 데이터 살아 있음
127.0.0.1:6379> SELECT 1 → KEYS *           → (empty array)
```

## CI

```
✅ 소스 체크아웃
✅ JDK 17 설정
✅ gradlew 실행 권한 부여
✅ 테스트 실행        BUILD SUCCESSFUL
✅ 테스트 리포트 업로드
```

## 설정 외부화

```
docker compose config
  MYSQL_DATABASE: rankingdb     ← .env 치환 확인
  MYSQL_PASSWORD: dev1234

docker compose ps
  ranking-mysql    Up (healthy)  ← $$ 이스케이프 동작 확인
```

**기본 프로파일** — 환경변수 없이 정상 기동 ✅
**운영 프로파일** — `JWT_SECRET` 없어 기동 실패 ✅ (의도대로)

## Actuator

```json
GET /actuator/info
{ "build": { "time": "2026-11-11T07:10:51.763Z", ... } }

GET /actuator/health
{
  "status": "UP",
  "components": { "db": UP, "redis": UP, "diskSpace": UP, "ssl": UP,
                  "livenessState": UP, "readinessState": UP },
  "groups": [ "liveness", "readiness" ]
}
```

---

# 8. README 구성 전략

면접관은 **30초 안에 "이 사람 뭘 했나"** 를 판단합니다.

| 배치 | 내용 | 이유 |
| --- | --- | --- |
| 최상단 | CI 배지 + 한 줄 소개 | 신뢰도 |
| **바로 아래** | **동시성 6방식 비교표** | **이 프로젝트의 전부** |
| 그다음 | 채택 근거 + "조용한 실패" | 왜 그렇게 했나 |
| 중간 | 배경 / 스택 / 빠른 시작 / 아키텍처 | 맥락 |
| 아래 | 트러블슈팅 8건 (`<details>` 접기) | 깊이 |
| 끝 | 알려진 한계 13개 | **의식적 선택의 증거** |

> **`<details>` 태그로 접은 것**이 핵심입니다. GitHub 마크다운이 지원하는 접기 기능으로, **스캔하는 사람은 제목만 보고 지나가고, 관심 있는 사람은 펼쳐서 읽습니다.**
>
> 트러블슈팅을 전부 펼쳐두면 **스크롤이 끝없이 길어져 아무도 안 읽습니다.** 정보를 버리지 않으면서 **첫인상을 짧게** 만드는 방법입니다.

> **"알려진 한계"를 적는 이유**: 몰라서 안 한 것과 **알고 범위를 그은 것**은 다릅니다. 13개를 "왜 안 했나 / 보완 방법"과 함께 적으면 **판단력의 증거**가 됩니다.

---

# 9. 프로젝트 2를 마치며

## 9-1. 최종 상태

| 항목 | 결과 |
| --- | --- |
| 단계 | 0~9 **전부 완료** |
| 테스트 | 격리된 전용 DB, CI 자동 실행 |
| 설정 | 외부화 + 운영 프로파일 |
| 문서 | README + 노션 작업 기록 7편 |

## 9-2. ⭐ 이 프로젝트를 관통한 세 가지

### ① 읽고-쓰는 사이에 틈을 만들지 않는다

| 영역 | 문제 있는 코드 | 해결 |
| --- | --- | --- |
| DB 카운터 | `findById` → `increaseLikeCount()` | `UPDATE SET x = x + 1` |
| 중복 검사 | `existsBy` → `save` | DB 유니크 제약 |
| Java 카운터 | `count++` | `AtomicInteger.incrementAndGet()` |
| Java 맵 | `put(k, getOrDefault(k,0)+1)` | `merge(k, 1, Integer::sum)` |
| Redis 수거 | `GET` → `DEL` | `GETDEL` |
| Redis 교체 | `DEL` → `ZADD` | 임시 키 + `RENAME` |

### ② 고장은 시끄러울수록 좋다

| 지점 | 어떻게 |
| --- | --- |
| HikariCP | 기동 시 커넥션 시험 → 실패하면 앱을 안 띄움 |
| Spring Data | 메서드 이름 오타를 **기동 시점에** |
| jjwt | 짧은 키를 **생성자에서** `WeakKeyException` |
| `@Version` | 조용한 유실 90건 → **명시적 예외 90건** |
| 메서드 삭제 | 호출부 수정 누락을 **컴파일 에러**로 |
| `ddl-auto: validate` | 스키마 불일치 시 **기동 실패** |
| `application-prod.yml` | 환경변수 누락 시 **기동 실패** |
| `@Tag` 분리 | 빨간불이 **항상 진짜 실패** |
| `/actuator/info` | **배포 시각을 응답에 노출** |

### ③ 요구사항이 다르면 설계도 다르다

| | 좋아요 수 | 조회수 |
| --- | --- | --- |
| 정확성 요구 | 높음 | 낮음 |
| Redis 역할 | 읽기 캐시 | 쓰기 버퍼 |
| 선택 | DB 원자적 연산 | Redis 증분 + 배치 |

## 9-3. "조용한 실패"가 네 번 등장했다

| 시점 | 무엇이 실패했나 | 왜 안 보였나 |
| --- | --- | --- |
| 5단계 | **데이터** — 좋아요 90건 유실 | 응답이 전부 200 OK |
| 8단계 | **배포** — 새 코드가 안 올라감 | 옛 프로세스가 200으로 응답 |
| 8단계 | **설계** — 재구축 무한 반복 | 데이터가 있는 동안 증상이 없음 |
| 9단계 | **환경** — 테스트가 개발 DB 파괴 | 에러 없이 조용히 지워짐 |

**층위는 다르지만 "실패했는데 성공처럼 보인다"는 구조가 같았고, 해결 방향도 같았습니다 — 고장을 시끄럽게 만들기.**

---

# 10. 다음에 할 일

## 즉시

- [ ] README.md 교체 후 커밋
- [ ] GitHub 저장소 About에 설명·토픽 추가 (`spring-boot`, `concurrency`, `redis`, `mysql`)
- [ ] CI 배지 동작 확인

## 포트폴리오 허브

- [ ] 공개 Notion 페이지 — 프로젝트 1·2 소개 + 저장소 링크
- [ ] 이력서에 넣을 **3~4줄 요약** 작성

> **"포트폴리오 링크"를 요청받았을 때 줄 수 있는 하나의 주소**가 필요합니다. 비공개 작업 기록과 분리해 **공개용 허브**를 따로 만듭니다.

## 프로젝트 3 (선택)

LLM 자동 태깅 API — 회사에서 쓰는 **OpenSearch와 연결**하면 "태깅 결과를 색인해 검색"까지 자연스럽게 이어집니다.

> 다만 **프로젝트 1·2만으로도 지원 가능한 상태**입니다. 프로젝트 3은 차별화를 위한 추가분이지 필수가 아닙니다.

## 프로젝트 2 향후 개선 (README에 기재됨)

| 항목 | 내용 |
| --- | --- |
| Flyway 도입 | `ddl-auto: update`에 세 번 당한 근거가 이미 문서화됨 |
| 분산 락 | 캐시 스탬피드 방지 |
| 역할 기반 인가 | 관리 API 보호 |
| Java 21 가상 스레드 | 측정 규모 확대 |
| Testcontainers | 완전한 테스트 격리 |
