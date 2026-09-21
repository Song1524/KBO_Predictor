# 운영 보안 메모

> 기준: 현재 `SecurityConfig`, `AuthController`, frontend `api-client.ts`, Spring profile 설정<br>
> 이 문서는 구현된 보안 동작과 아직 구현되지 않은 항목을 구분합니다.

## 인증과 비밀번호

- 인증 방식은 JWT가 아니라 Spring Security의 server-side `HttpSession`입니다.
- `DaoAuthenticationProvider`가 `KboUserDetailsService`와 `BCryptPasswordEncoder`를 사용합니다.
- 회원가입 비밀번호는 BCrypt hash로 저장합니다. 비밀번호 원문을 저장하거나 응답하지 않습니다.
- `ACTIVE` 상태 사용자만 활성 계정으로 인증되고 DB role은 `ROLE_USER` 또는 `ROLE_ADMIN` authority로 변환됩니다.
- 로그인·회원가입 성공 시 기존 session을 invalidate하고 새 session에 `SecurityContext`를 명시적으로 저장해 session fixation을 줄입니다.
- session은 현재 애플리케이션 memory에 저장됩니다. Spring Session/Redis는 사용하지 않으므로 backend 재시작 시 session이 사라지고 여러 instance가 session을 공유하지 못합니다.

근거: `SecurityConfig`, `AuthController.establishSession()`, `KboUserDetailsService`, `SignupService`.

## Session cookie

공통 cookie 이름은 `PLAYBALL_SESSION`이고 cookie tracking만 사용합니다.

| Profile | Timeout | Secure | HttpOnly | SameSite |
|---|---:|---:|---:|---:|
| `local` | 8시간 기본값 | false | true | Lax |
| `test` | 1시간 | false | true | Lax |
| `prod` | 2시간 기본값 | true | true | Lax |

cookie path는 `/`입니다. 운영은 HTTPS reverse proxy를 전제로 하며 `server.forward-headers-strategy=framework`를 사용합니다. Nginx는 `X-Forwarded-Proto`, host, port와 client IP 계열 header를 backend에 전달합니다.

## CSRF

CSRF는 **현재 활성화되어 있습니다.**

- backend는 `CookieCsrfTokenRepository.withHttpOnlyFalse()`를 사용합니다.
- `GET /api/auth/csrf`가 token 생성을 유도하고 `XSRF-TOKEN` cookie를 발급합니다.
- 이 token cookie는 frontend JavaScript가 읽어야 하므로 HttpOnly가 아닙니다. session cookie인 `PLAYBALL_SESSION`은 별도로 HttpOnly입니다.
- frontend `apiFetch()`는 `POST`, `PUT`, `PATCH`, `DELETE` 전에 token을 확보하고 `X-XSRF-TOKEN` header로 전송합니다.
- token 발급과 header 검증은 `CsrfCookieIntegrationTest`에서, CSRF 없는 변경 요청 거부는 auth/community web test에서 검증합니다.

CSRF token cookie의 `Secure`/`SameSite` 속성을 코드에서 별도로 고정하지는 않습니다. 운영에서는 HTTPS와 동일 origin reverse proxy 구성이 전제입니다.

## CORS

- `/api/**`는 credential CORS를 사용합니다.
- 허용 origin은 `app.web.cors.allowed-origins`에서 명시적으로 받습니다.
- `*`는 session credential과 함께 사용할 수 없도록 애플리케이션 시작 단계에서 거부합니다.
- 허용 method는 `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`이고 credential을 허용합니다.
- local/test 기본 origin은 `http://localhost:5173`, prod는 `APP_FRONTEND_ORIGIN` 환경변수가 필요합니다.

CORS는 브라우저의 cross-origin 응답 접근 정책이며 CSRF 방어를 대체하지 않습니다. 이 프로젝트는 explicit origin과 CSRF token을 함께 사용합니다.

## 인가 정책

- `/api/admin/**`는 `ROLE_ADMIN`만 접근할 수 있습니다.
- 사용자 예측, 포인트 이력, 현재 사용자, logout과 community 변경 요청은 인증이 필요합니다.
- 경기·팀·순위·랭킹 및 community 조회 API는 public read로 허용합니다.
- 명시적으로 등록하지 않은 요청은 `denyAll`입니다.
- 회원가입은 항상 `USER` role을 만들며 초기 ADMIN 계정을 migration이나 환경변수로 자동 생성하지 않습니다.

## 로그

현재 코드에서 확인되는 보안·운영 로그는 다음과 같습니다.

- 로그인 실패 사유와 remote address
- KBO 일정/상태/팀 통계/선발투수 동기화 시작·완료·실패
- 배당 마감과 경기 정산/환불
- 시스템·shadow 예측 생성과 평가 실패
- `/api/admin/**`의 method/path/status/실행 시간

관리자 로그에는 현재 principal, request payload, 변경 전후 값을 남기는 영속 audit trail이 없습니다. 요청 body, password, session id, DB credential을 의도적으로 기록하는 코드도 확인되지 않았지만, 운영 log 수집기의 접근 권한과 보존 기간은 별도로 관리해야 합니다.

## 현재 남은 보안 과제

- 로그인 brute-force 방지용 account/IP rate limit 또는 lockout 없음
- email verification, password reset, MFA, OAuth/OIDC login 없음
- Redis/Spring Session 기반 session 공유·재시작 보존 없음
- 관리자 작업의 actor·변경 전후를 보존하는 DB audit log 없음
- reverse proxy의 허용 Host, 요청 크기, 세부 security header 정책을 repository에서 일관되게 검증하는 test 없음
- secret rotation, container MySQL backup/restore, 중앙 log 보존 정책은 repository 밖의 운영 과제로 남음
