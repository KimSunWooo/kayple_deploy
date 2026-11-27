# 케이플 상담 예약 서비스

1:1 상담 예약 웹 서비스입니다. 동일 날짜·시간에는 활성 예약 1건만 허용하며, 예약/취소 문자는 Transactional Outbox로 비동기 발송합니다.

## 기술 스택

- Frontend: React, Vite, TypeScript, CSS
- Backend: Spring Boot 3.5, Java 21, Maven Wrapper, Spring JDBC, Spring Security(세션)
- DB: MySQL 8.0 (`CREATE TABLE IF NOT EXISTS`로 최초 기동 시 스키마 생성, 재시작 시 데이터 유지)
- 배포: Nginx(SPA + `/api` reverse proxy), Spring Boot JAR, systemd(`kayple`)

## 로컬 실행

### 필수 환경변수

| 변수 | 설명 |
| --- | --- |
| `DB_URL` | JDBC URL (예: `jdbc:mysql://127.0.0.1:3306/app?...&serverTimezone=Asia/Seoul`) |
| `DB_USERNAME` | DB 사용자 |
| `DB_PASSWORD` | DB 비밀번호 |
| `ADMIN_USERNAME` | 관리자 아이디 |
| `ADMIN_PASSWORD` | 관리자 비밀번호(평문, 기동 시 BCrypt 인코딩) |
| `SMS_API_KEY` | 문자 API Bearer 키 |

예시: `.env.example` 참고. 실제 값은 커밋하지 마세요.

### Backend

```bash
cd backend
export DB_URL=... DB_USERNAME=... DB_PASSWORD=...
export ADMIN_USERNAME=... ADMIN_PASSWORD=... SMS_API_KEY=...
./mvnw spring-boot:run
```

- 바인딩: `127.0.0.1:8080`
- `X-Forwarded-Proto` / `X-Forwarded-For` 인식 (`server.forward-headers-strategy=framework`)

### Frontend

```bash
cd frontend
npm install
npm run dev   # Vite proxy: /api -> http://127.0.0.1:8080
```

## 테스트 실행 및 결과

실행하지 않은 항목은 성공으로 적지 않습니다. Docker가 필요하면 `docker` 그룹이 활성 세션에 있어야 합니다 (`newgrp docker` 또는 `sg docker -c '…'`).

### A. 로컬 단위 테스트 (Mock)

```bash
cd backend
./mvnw -Dtest=SmsWorkerTest,ReservationServiceTest,AdminSecurityTest test
```

**실제 결과 (2026-11-27 18:12 KST, 이후 통합 스위트에 포함해 재확인):**

```
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
```

| 테스트 | 건수 | 내용 |
| --- | --- | --- |
| `ReservationServiceTest` | 4 | 예약+Outbox 순서, DuplicateKey 전파, 중복 취소 거부, 외부 SMS 미호출 |
| `SmsWorkerTest` | 5 | 503 재시도, 504 동일 Idempotency-Key, 400 FAILED, 429 Retry-After, 백오프 |
| `AdminSecurityTest` | 2 | 미인증 관리자 API 401, 로그인 성공 |

### B. Testcontainers MySQL 통합 테스트 (로컬 Docker)

```bash
# Docker 그룹이 세션에 없으면:
sg docker -c './mvnw -Dtest=ReservationIntegrationTest,OutboxIntegrationTest,SmsWorkerIntegrationTest,AdminAuthIntegrationTest,SmsWorkerTest,ReservationServiceTest,AdminSecurityTest test'
```

환경: MySQL **8.0** Testcontainers + WireMock(SMS Worker IT). H2로 대체하지 않음.  
Docker Engine 29 호환을 위해 `api.version=1.44` (`docker-java.properties`, Surefire argLine).

**실제 결과 (2026-11-27 18:26 KST):**

```
Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
Maven total time: 22.854 s
BUILD SUCCESS
```

| 구분 | 클래스 | 건수 | 시간 |
| --- | --- | ---: | ---: |
| 관리자 인증 IT | `AdminAuthIntegrationTest` | 5 | 9.3s |
| Outbox IT | `OutboxIntegrationTest` | 6 | 0.6s |
| 예약 IT | `ReservationIntegrationTest` | 7 | 0.6s |
| SMS Worker IT | `SmsWorkerIntegrationTest` | 11 | 9.2s |
| 단위(Mock) | `AdminSecurityTest`+`SmsWorkerTest`+`ReservationServiceTest` | 11 | <1s |

검증 범위 요약: 동시 8요청 1성공/7×409, 취소 이력·재예약, 연락처 조회/타번호 취소 차단, Outbox 트랜잭션·롤백·멱등키 UNIQUE, SMS 200/503/504/409/429/400/401·지연·stale PROCESSING·워커 동시 선점, 관리자 세션·CSRF 로그아웃.

### C. Playwright E2E (로컬 격리 스택)

```bash
cd frontend && npm install && npx playwright install chromium
chmod +x ../scripts/run-e2e.sh
../scripts/run-e2e.sh
npx playwright show-report   # HTML Report
# 또는: npm run test:e2e / npm run test:e2e:report
```

구성: `docker-compose.e2e.yml`(MySQL 3307 + WireMock 8089) + Spring Boot JAR + Vite. 운영 서버/DB는 사용하지 않음. 로컬 HTTP 관리자 로그인을 위해 `SESSION_COOKIE_SECURE=false`.

**실제 결과 (2026-11-27):** Playwright **8 passed / 0 failed / 0 skipped**, 약 **6.2s** (스택 기동 포함 스크립트 ~23s).

| 스펙 | 내용 |
| --- | --- |
| `user-booking.spec.ts` | 예약→마감→조회→취소→재예약 |
| `validation.spec.ts` | 이름/연락처/시간/마감/빈 조회 |
| `admin.spec.ts` | 로그인 실패·성공·목록·문자상태·로그아웃 |
| `sms-status.spec.ts` | WireMock 기반 SENT/대기/FAILED UI |

### D. 배포 서버 통합 검증 (`https://ksw.recruit.kayple.com`)

검증 시각: 2026-11-27 (Asia/Seoul). 상세: `tmp-screens/verification-results.md` (커밋 비대상). **수치 변경 없음.**

| # | 항목 | 결과 | 근거 |
| --- | --- | --- | --- |
| 1 | `nginx -t` / LE include | **PASS** | `options-ssl-nginx.conf`, `ssl-dhparams.pem` 존재, syntax ok |
| 2 | 동일 슬롯 동시 예약 8건 | **PASS** | HTTP 200 ×1, 409 `SLOT_TAKEN` ×7 |
| 3 | 조회 → 취소 → 동일 시간 재예약 | **PASS** | search/cancel/rebook 모두 200 |
| 4 | `01000000008` 예약 응답 지연 | **PASS** | 응답 **27ms** (문자 8초 대기 없음) |
| 5A | `01000000503` 503 재시도 | **PASS** | outbox `PENDING`, attempt_count 증가, last_error 503 |
| 5B | `01000000504` 504 멱등 | **PASS** | Idempotency-Key 불변, 제공자 메시지 **1건**, 최종 SENT |
| 5C | `01000000400` 400 영구실패 | **PASS** | outbox `FAILED`/`RECIPIENT_BLOCKED`, 재시도 중단 |
| 6 | 관리자 미인증/로그인 | **PASS** | HTTPS에서 미인증 401, 로그인 후 me·목록 200, 문자 상태 표시 |

참고: 운영은 `SESSION_COOKIE_SECURE=true`(HTTPS). 모의 SMS(WireMock/IT)와 실제 SMS API(배포 검증)는 목적이 다름 — IT/E2E는 제어된 응답, 배포 검증은 과제용 실 API.

### 테스트 중 발견한 코드 수정

- `BookingPage`: 「처음으로」 후 availability를 다시 불러 방금 예약한 슬롯이 마감으로 보이게 함 (E2E에서 발견한 UI 갱신 누락).

## 빌드 산출물

| 구분 | 경로 |
| --- | --- |
| Backend 실행 JAR | `backend/target/reservation-0.0.1-SNAPSHOT.jar` |
| 배포용 복사본 | `backend/app.jar` |
| Frontend dist | `frontend/dist/` (`index.html`, `assets/`) |

서버 배포 시:

- JAR → `/home/ubuntu/kayple-reservation/backend/app.jar`
- dist → `/var/www/html/`
- 환경변수 → `/etc/kayple/app.env`
- systemd → `kayple.service`

Nginx/HTTPS는 서버에서 별도 설정합니다. 템플릿만 `deploy/nginx-kayple.conf`, `deploy/kayple.service`, `deploy/app.env.example`에 있습니다.

## 구현한 기능

- 오늘~7일(Asia/Seoul) 날짜·시간(10/11/13–17) 예약 UI
- 마감/지난 시간 선택 불가, 이름·연락처 검증(FE+BE), 연락처 숫자 정규화
- 예약 완료 / 연락처 조회 / 취소 확인 모달
- DB generated column `active_slot` + UNIQUE로 활성 슬롯 중복 방지
- 예약·취소와 SMS Outbox를 동일 트랜잭션으로 기록 (API 중 외부 SMS 미호출)
- Outbox Worker: 선점 커밋 후 HTTP 호출, 초당 5건 제한, 재시도/복구/24시간 중단
- 관리자 세션 로그인·목록·문자 상태(발송 완료/대기/실패)
- CSRF(쿠키) + 관리자 API 서버 차단

## 구현하지 못한 / 제한 사항

- UI는 기능 우선, 시안 대비 픽셀 단위 디자인은 미흡
- GitHub 공개 저장소 push / 과제 제출 폼 입력은 아직 사용자 작업
- Playwright는 Chromium만 실행 (Firefox/WebKit 미실행)
- Docker 세션에 `docker` 그룹이 없으면 Testcontainers/E2E 스크립트 실행 불가 (`newgrp docker` 필요)

## 문자 API 지연·실패 대응

1. 예약/취소 API는 Outbox INSERT만 하고 즉시 응답
2. Worker가 `PENDING`을 `PROCESSING`으로 선점(트랜잭션 커밋)한 뒤 SMS API 호출
3. 200 → SENT, 400/401/409(키 재사용) → FAILED, 429 → Retry-After, 503/504/네트워크 → 동일 키로 백오프 재시도
4. 첫 시도 후 24시간 초과 시 자동 재시도 중단 + `MANUAL_CHECK_REQUIRED` FAILED (멱등 보장 기간)

## 중복 예약 방지

`reservations.active_slot`은 `CONFIRMED`일 때만 `1`, 취소 시 `NULL`.  
`UNIQUE(reservation_date, reservation_hour, active_slot)`로 DB가 보장. 앱은 SELECT-후-INSERT에 의존하지 않으며 `DuplicateKeyException` → HTTP 409.

## AI 생성 코드에서 직접 검토·수정이 필요한 부분

사용자가 아직 손으로 전부 검수하지 않은 항목입니다. 특히 확인을 권장합니다.

1. Spring Security CSRF 예외 경로(`/api/reservations/**`, login)와 관리자 POST의 XSRF 쿠키 연동
2. Outbox `FOR UPDATE SKIP LOCKED` 선점 SQL과 stale `PROCESSING` 복구 시간(90초)
3. 관리자 비밀번호를 기동마다 BCrypt 인코딩하는 방식(인메모리 UserDetails) — 운영 정책에 맞는지
4. 프론트 유효성·오류 메시지 문구와 시안 일치 여부
5. `/etc/kayple/app.env` 실제 값 및 `SESSION_COOKIE_SECURE=true` (HTTPS)

## 팀원에게 넘길 작업 지시 3개

1. CI에서 `sg docker`/`docker` 그룹이 있는 러너로 `./mvnw test`와 `scripts/run-e2e.sh`를 매 PR에 돌린다.
2. 시안 대비 문구·반응형만 다듬고 Playwright assertion 문구를 함께 갱신한다.
3. 공개 GitHub push 전 실키/`*.pem`/`app.env` 혼입 여부를 재확인하고 제출 폼에 관리자 비밀번호 메모와 AI 대화 요약을 올린다.

## 배포 참고

```bash
# 로컬 빌드 후 업로드 예시 (Nginx는 사용자가 설정 중이면 건드리지 말 것)
scp -i ~/.ssh/kayple/recruit-ksw.pem backend/app.jar ubuntu@ksw.recruit.kayple.com:/home/ubuntu/kayple-reservation/backend/app.jar
rsync -az -e "ssh -i ~/.ssh/kayple/recruit-ksw.pem" frontend/dist/ ubuntu@ksw.recruit.kayple.com:/tmp/frontend-dist/
# 서버에서 dist를 /var/www/html 로 반영 후: sudo systemctl restart kayple
```
