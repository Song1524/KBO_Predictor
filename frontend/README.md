# PLAYBALL - KBO 승부예측 프론트엔드

v0에서 생성한 Next.js UI를 Vite + React + TypeScript 구조로 변환한 프로젝트입니다.

## 기술 구성

- Vite
- React 19
- TypeScript
- Tailwind CSS 4
- shadcn/ui

## 실행 방법

```bash
npm install
npm run dev
```

개발 서버 기본 주소는 `http://localhost:5173`입니다.

## Spring Boot 연결

`vite.config.ts`에 다음 개발용 프록시가 설정되어 있습니다.

- `/api` → `http://localhost:8080`
- `/ws` → `http://localhost:8080`

따라서 프론트에서는 `fetch('/api/games')`처럼 호출할 수 있습니다.

## 프로덕션 빌드

```bash
npm run build
npm run preview
```

빌드 결과물은 `dist/` 폴더에 생성됩니다.
