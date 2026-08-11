# 운영 보안 메모

## 세션과 쿠키

운영 profile은 HTTPS를 전제로 `PLAYBALL_SESSION` 쿠키에 `Secure`, `HttpOnly`, `SameSite=Lax`를 적용합니다. 로컬 profile만 HTTP 개발을 위해 `Secure=false`를 사용합니다. 운영 reverse proxy는 원래 scheme의 `X-Forwarded-Proto`를 전달해야 합니다.

## CORS

세션 쿠키 요청은 credential CORS를 사용합니다. `APP_FRONTEND_ORIGIN`에 정확한 HTTPS Origin을 설정해야 하며 `*`는 애플리케이션 시작 단계에서 거부됩니다. 여러 Origin이 필요한 경우 쉼표로 구분하되, 신뢰할 수 있는 고정 Origin만 사용합니다.

CORS는 브라우저 응답 읽기를 제한할 뿐 완전한 CSRF 방어가 아닙니다.

## 현재 CSRF 상태

Spring Security CSRF는 기존 JSON API와 프론트 계약을 유지하기 위해 현재 비활성화되어 있습니다. 현재 완화 요소는 다음과 같습니다.

- SameSite=Lax 세션 쿠키
- 운영 Secure/HttpOnly 쿠키
- credential 요청의 명시적 CORS Origin allowlist
- 변경 API가 JSON body를 사용하며 관리자 API는 ROLE_ADMIN으로 보호됨

남은 위험은 same-site 하위 도메인 침해, 잘못된 reverse proxy/Origin 설정, 브라우저 정책 예외입니다. 인터넷 공개 전에는 다음 후속 작업을 권장합니다.

1. Spring Security CSRF를 cookie 기반 token repository로 활성화합니다.
2. 프론트 fetch 공통 계층에서 `X-XSRF-TOKEN`을 모든 변경 요청에 전송합니다.
3. 로그인·회원가입·로그아웃·예측·관리자 변경 API 회귀 테스트를 추가합니다.
4. 배포 proxy에서 HTTPS 강제, HSTS, 허용 Host 검증과 요청 크기 제한을 적용합니다.

CSRF 토큰 적용은 모든 변경 요청 계약에 영향을 주므로 이번 환경 정리에서는 활성화하지 않았습니다.

## 로그

현재 운영 로그에서 추적하는 주요 이벤트:

- KBO 일정/상태/팀 통계/선발투수 동기화 시작·완료·실패
- 배당 자동 마감 및 중복 마감 건너뜀
- 경기 자동 정산과 취소 환불
- 시스템/Shadow 예측 생성과 Shadow 평가 실패
- 로그인 실패 사유와 원격 주소
- `/api/admin/**` 요청 method/path/status/실행 시간

요청 body, 비밀번호, 세션 ID, DB 자격 증명은 로그에 기록하지 않습니다. 운영 로그 수집기는 접근 권한과 보존 기간을 별도로 제한해야 합니다.
