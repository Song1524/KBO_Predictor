# PLAYBALL KBO Predictor

Spring Boot 4 / Java 21 백엔드와 React / Vite 프론트엔드로 구성된 KBO 승부예측 서비스입니다. DB 스키마는 Flyway가 관리하며 운영 기본 예측 모델은 `baseline-v1`입니다.

## 필요 프로그램

- Java 21
- Node.js 22 이상과 npm
- MySQL 8.4 이상
- 전체 컨테이너 실행 시 Docker Engine 및 Docker Compose v2

## 환경별 설정

백엔드는 다음 Spring profile을 사용합니다.

| Profile | 용도 | DB | 세션 쿠키 |
|---|---|---|---|
| `local` | 로컬 개발, 기본 profile | `DB_*` 환경변수, localhost 기본 URL | HTTP 허용, HttpOnly, SameSite=Lax |
| `prod` | 운영/Docker | `DB_*` 필수 | Secure, HttpOnly, SameSite=Lax |
| `test` | 자동 테스트 | 별도 `TEST_DB_*` | 스케줄러 비활성화 |

공통 설정은 `backend/src/main/resources/application.yaml`, 환경별 값은 `application-local.yaml`, `application-prod.yaml`, `application-test.yaml`에 있습니다.

## 환경변수

| 이름 | 설명 |
|---|---|
| `DB_URL` | local/prod JDBC URL |
| `DB_USERNAME` | local/prod DB 사용자 |
| `DB_PASSWORD` | local/prod DB 비밀번호 |
| `TEST_DB_URL` | 테스트 전용 JDBC URL |
| `TEST_DB_USERNAME` | 테스트 DB 사용자 |
| `TEST_DB_PASSWORD` | 테스트 DB 비밀번호 |
| `APP_FRONTEND_ORIGIN` | credential CORS를 허용할 정확한 프론트 Origin |
| `SESSION_TIMEOUT` | 세션 만료 시간, prod 기본 2시간 |
| `SESSION_COOKIE_SECURE` | prod 기본 true. localhost HTTP compose 검증에서만 false |
| `SERVER_PORT` | 백엔드 포트, 기본 8080 |

Docker Compose 변수는 루트의 `.env.example`을 참고합니다. `.env`는 Git에서 제외되며 실제 비밀번호를 저장소에 커밋하지 않습니다.

## 로컬 실행

PowerShell 예시입니다.

```powershell
$env:DB_URL='jdbc:mysql://localhost:3306/kbo_predictor?connectionTimeZone=Asia/Seoul&characterEncoding=UTF-8'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='<local-password>'
$env:APP_FRONTEND_ORIGIN='http://localhost:5173'

cd backend
.\gradlew.bat bootRun
```

다른 터미널에서 프론트엔드를 실행합니다.

```powershell
cd frontend
npm ci
npm run dev
```

`local`은 기본 profile이므로 별도 profile 인자가 없어도 됩니다.

## Docker 실행

```powershell
Copy-Item .env.example .env
# .env의 비어 있는 비밀번호 값을 안전한 값으로 설정
docker compose up --build
```

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8080`
- Health: `http://localhost:8080/actuator/health`

MySQL은 호스트에 직접 노출하지 않습니다. Backend는 MySQL healthcheck가 성공한 뒤 시작되고, Frontend는 Backend healthcheck가 성공한 뒤 시작됩니다.

`.env.example`의 `SESSION_COOKIE_SECURE=false`는 localhost HTTP 검증 전용입니다. 인터넷 HTTPS 배포에서는 `APP_FRONTEND_ORIGIN`을 실제 HTTPS Origin으로 바꾸고 `SESSION_COOKIE_SECURE=true`를 반드시 설정합니다.

```powershell
docker compose down
```

DB 데이터는 `mysql-data` named volume에 유지됩니다. 완전한 빈 DB 재검증이 필요한 경우에만 별도 프로젝트 이름 또는 새 volume을 사용하십시오. 기존 개발 DB나 운영 volume을 삭제하지 마십시오.

## GitHub Actions 배포

`main` 브랜치 push 시 [.github/workflows/deploy.yml](.github/workflows/deploy.yml)이 다음 순서로 실행됩니다.

1. Java 21/MySQL 8.4 환경에서 Backend 전체 테스트
2. Node.js 22 환경에서 Frontend production build
3. 두 CI job 성공 후 GitHub OIDC로 AWS IAM Role assume
4. AWS Systems Manager `AWS-RunShellScript`로 EC2 배포
5. SSM Command 상태를 제한 시간 동안 polling
6. Backend/Frontend health check와 최종 `docker compose ps` 출력

GitHub Repository Variables에는 다음 값이 필요합니다.

- `AWS_REGION`
- `AWS_ROLE_ARN`
- `EC2_INSTANCE_ID`

정적 AWS access key와 SSH key는 사용하지 않습니다. GitHub OIDC IAM Role의 trust policy는 `Song1524/KBO_Predictor` 저장소의 `main` 브랜치만 role을 assume할 수 있도록 제한해야 합니다.

EC2에는 다음 조건이 준비되어 있어야 합니다.

- SSM Agent Online 및 `AmazonSSMManagedInstanceCore`가 적용된 instance profile
- `/home/ubuntu/KBO_Predictor` clone과 `origin/main` fetch 권한
- Docker Engine, Docker Compose v2
- `ubuntu` 사용자의 Docker 실행 권한
- Git에서 제외된 운영 `.env`

SSM Agent가 command를 root로 받아도 Git 작업과 Docker 배포는 `ubuntu` 사용자로 실행하여 저장소와 `.env` 소유권을 보존합니다. SSM 배포는 IP 주소나 22번 포트를 사용하지 않습니다. 실패 시 GitHub Actions 로그에 SSM Status, ResponseCode, stdout, stderr가 출력됩니다.

## Flyway

애플리케이션 시작 시 Flyway V1부터 최신 migration까지 순서대로 적용되고 Hibernate는 `ddl-auto=validate`로 스키마를 검증합니다. `clean`은 비활성화되어 있습니다.

- V1~V10: 기존 스키마와 기능 migration
- V11: V3의 과거 로컬 테스트 계정을 prod/test에서만 제거
- KBO 10개 팀 기준 데이터는 V6에서 유지

새 DB 변경은 기존 migration을 수정하지 말고 다음 버전의 migration 파일로 추가합니다.

## 테스트

테스트는 개발 DB와 다른 MySQL schema를 사용해야 합니다.

```powershell
$env:TEST_DB_URL='jdbc:mysql://localhost:3306/kbo_predictor_test?createDatabaseIfNotExist=true&connectionTimeZone=Asia/Seoul&characterEncoding=UTF-8'
$env:TEST_DB_USERNAME='root'
$env:TEST_DB_PASSWORD='<test-db-password>'

cd backend
.\gradlew.bat test
```

```powershell
cd frontend
npm ci
npm run build
```

## 관리자 계정 준비

회원가입 API는 항상 `USER` 권한만 생성합니다. 먼저 정상 회원가입한 뒤 권한이 있는 DB 관리자가 해당 사용자를 명시적으로 승격합니다.

```sql
UPDATE users
SET role = 'ADMIN', updated_at = CURRENT_TIMESTAMP
WHERE email = '<admin-email>' AND provider = 'LOCAL';
```

관리자 비밀번호나 초기 ADMIN 계정은 migration 및 Docker 환경에서 자동 생성하지 않습니다.

## 운영 보안

- 운영은 HTTPS reverse proxy 뒤에서 실행해야 합니다.
- 운영 세션 쿠키는 Secure, HttpOnly, SameSite=Lax입니다.
- CORS는 `APP_FRONTEND_ORIGIN` 한정이며 wildcard를 거부합니다.
- Actuator는 health endpoint만 외부 노출합니다.
- CSRF는 현재 비활성화 상태입니다. 위험과 배포 전 후속 조치는 [보안 운영 문서](docs/SECURITY.md)를 확인하십시오.
- 비밀번호, 세션 ID, DB 자격 증명은 애플리케이션 로그에 기록하지 않습니다.

## ML artifact

`backend/ml/artifacts/logistic-v1.json`은 Gradle `processResources` 단계에서 JAR의 `models/logistic-v1.json`으로 포함됩니다. 시작 시 고정 SHA-256을 검증하며 파일이 변경되면 애플리케이션이 실패합니다. 운영 active model은 계속 `baseline-v1`입니다.
