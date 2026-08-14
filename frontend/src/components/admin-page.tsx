import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Activity,
  AlertTriangle,
  BarChart3,
  Bot,
  CalendarClock,
  CheckCircle2,
  Database,
  LockKeyhole,
  RefreshCw,
  ServerCog,
  ShieldCheck,
  Swords,
  Users,
  XCircle,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/auth-context'
import { AppHeader } from '@/components/app-header'
import { Badge } from '@/components/ui/badge'
import { Button, buttonVariants } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'
import type {
  AdminGameResponse,
  AdminSummaryResponse,
  BackfillResponse,
  GameModelComparisonResponse,
  GameStatus,
  GameSyncResponse,
  HistoricalEvaluationResponse,
  PredictionGenerationBatchResponse,
  PredictionGenerationResponse,
  ShadowEvaluationResponse,
  StartingPitcherSyncResponse,
  TeamStatsSyncResponse,
} from '@/lib/admin-api-types'
import { cn } from '@/lib/utils'

type Confirmation = {
  key: string
  title: string
  description: string
  execute: () => Promise<void>
}

const statusLabels: Record<GameStatus, string> = {
  SCHEDULED: '예정',
  IN_PROGRESS: '진행 중',
  FINISHED: '종료',
  CANCELLED: '취소',
}

const statusVariants: Record<
  GameStatus,
  'default' | 'secondary' | 'destructive' | 'outline'
> = {
  SCHEDULED: 'outline',
  IN_PROGRESS: 'default',
  FINISHED: 'secondary',
  CANCELLED: 'destructive',
}

function localDate() {
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60_000
  return new Date(now.getTime() - offset).toISOString().slice(0, 10)
}

function formatDateTime(value: string | null) {
  if (!value) return '-'
  return new Date(value).toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function metric(value: number | null | undefined) {
  return value == null ? '-' : Number(value).toFixed(4)
}

function percent(value: number | null | undefined) {
  return value == null ? '-' : `${(Number(value) * 100).toFixed(2)}%`
}

function signedMetric(value: number | null | undefined, asPercent = false) {
  if (value == null) return '-'
  const amount = asPercent ? Number(value) * 100 : Number(value)
  return `${amount >= 0 ? '+' : ''}${amount.toFixed(asPercent ? 2 : 4)}${asPercent ? '%p' : ''}`
}

function confidenceInterval(
  lower: number | null | undefined,
  upper: number | null | undefined,
  asPercent = false,
) {
  if (lower == null || upper == null) return '표본 부족'
  return `[${signedMetric(lower, asPercent)}, ${signedMetric(upper, asPercent)}]`
}

async function requestJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    credentials: 'include',
    ...init,
    headers: init?.body
      ? { 'Content-Type': 'application/json', ...init.headers }
      : init?.headers,
  })
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    const fallback = (() => {
      switch (response.status) {
        case 401: return '로그인이 만료되었습니다. 다시 로그인해 주세요.'
        case 403: return '관리자 권한이 필요한 작업입니다.'
        case 400: return '요청 값과 날짜 범위를 확인해 주세요.'
        case 409: return '현재 상태와 충돌하여 작업을 처리할 수 없습니다.'
        default: return '서버에서 관리자 작업을 처리하지 못했습니다.'
      }
    })()
    throw new Error(body?.message ?? body?.detail ?? fallback)
  }
  return body as T
}

function SummaryCard({
  label,
  value,
  icon,
}: {
  label: string
  value: number
  icon: React.ReactNode
}) {
  return (
    <Card className="gap-3 py-4">
      <CardContent className="flex items-center justify-between px-4">
        <div>
          <p className="text-xs font-medium text-muted-foreground">{label}</p>
          <p className="mt-1 font-mono text-2xl font-black">{value.toLocaleString()}</p>
        </div>
        <div className="flex size-10 items-center justify-center rounded-xl bg-primary/10 text-primary">
          {icon}
        </div>
      </CardContent>
    </Card>
  )
}

function ResultMetrics({ values }: { values: Array<[string, number]> }) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
      {values.map(([label, value]) => (
        <div key={label} className="rounded-lg border bg-muted/30 p-3">
          <p className="text-xs text-muted-foreground">{label}</p>
          <p className="mt-1 font-mono text-lg font-bold">{value.toLocaleString()}</p>
        </div>
      ))}
    </div>
  )
}

function ErrorList({ errors }: { errors: string[] }) {
  if (errors.length === 0) return null
  return (
    <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-3">
      <p className="mb-2 text-sm font-semibold text-destructive">오류 목록</p>
      <ul className="grid gap-1 text-xs text-destructive">
        {errors.map((error, index) => <li key={`${error}-${index}`}>• {error}</li>)}
      </ul>
    </div>
  )
}

export function AdminPage() {
  const { user, isLoading: authLoading } = useAuth()
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'ROLE_ADMIN'
  const today = useMemo(localDate, [])
  const year = today.slice(0, 4)
  const [selectedDate, setSelectedDate] = useState(today)
  const [summary, setSummary] = useState<AdminSummaryResponse | null>(null)
  const [games, setGames] = useState<AdminGameResponse[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [pageError, setPageError] = useState('')
  const [notice, setNotice] = useState('')
  const [busyAction, setBusyAction] = useState<string | null>(null)
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)

  const [gameSync, setGameSync] = useState<GameSyncResponse | null>(null)
  const [teamStatsSync, setTeamStatsSync] = useState<TeamStatsSyncResponse | null>(null)
  const [pitcherSync, setPitcherSync] = useState<StartingPitcherSyncResponse | null>(null)
  const [predictionBatch, setPredictionBatch] = useState<PredictionGenerationBatchResponse | null>(null)
  const [singlePrediction, setSinglePrediction] = useState<PredictionGenerationResponse | null>(null)

  const [shadowFrom, setShadowFrom] = useState(`${year}-03-01`)
  const [shadowTo, setShadowTo] = useState(`${year}-10-31`)
  const [shadow, setShadow] = useState<ShadowEvaluationResponse | null>(null)
  const [shadowLoading, setShadowLoading] = useState(false)
  const [comparison, setComparison] = useState<GameModelComparisonResponse | null>(null)

  const [backfillFrom, setBackfillFrom] = useState(`${year}-05-01`)
  const [backfillTo, setBackfillTo] = useState(today)
  const [syncGamesForBackfill, setSyncGamesForBackfill] = useState(false)
  const [backfill, setBackfill] = useState<BackfillResponse | null>(null)
  const [historicalEvaluation, setHistoricalEvaluation] =
    useState<HistoricalEvaluationResponse | null>(null)

  const loadSummary = useCallback(async () => {
    setSummary(await requestJson<AdminSummaryResponse>(
      '/api/admin/dashboard/summary',
    ))
  }, [])

  const loadGames = useCallback(async () => {
    setGames(await requestJson<AdminGameResponse[]>(
      `/api/games?date=${selectedDate}`,
    ))
  }, [selectedDate])

  const loadShadow = useCallback(async () => {
    try {
      setShadowLoading(true)
      setPageError('')
      setShadow(await requestJson<ShadowEvaluationResponse>(
        `/api/admin/predictions/shadow/evaluation?from=${shadowFrom}&to=${shadowTo}`,
      ))
    } catch (error) {
      setPageError(error instanceof Error ? error.message : 'Shadow 평가 조회에 실패했습니다.')
    } finally {
      setShadowLoading(false)
    }
  }, [shadowFrom, shadowTo])

  const refreshOverview = async () => {
    try {
      setIsLoading(true)
      setPageError('')
      await Promise.all([loadSummary(), loadGames()])
    } catch (error) {
      setPageError(error instanceof Error ? error.message : '운영 요약을 갱신하지 못했습니다.')
    } finally {
      setIsLoading(false)
    }
  }

  useEffect(() => {
    if (authLoading || !isAdmin) return
    const load = async () => {
      try {
        setIsLoading(true)
        setPageError('')
        await Promise.all([loadSummary(), loadGames(), loadShadow()])
      } catch (error) {
        setPageError(error instanceof Error ? error.message : '관리자 데이터를 불러오지 못했습니다.')
      } finally {
        setIsLoading(false)
      }
    }
    void load()
  }, [authLoading, isAdmin, loadSummary, loadGames, loadShadow])

  const requestConfirmation = (
    key: string,
    title: string,
    description: string,
    execute: () => Promise<void>,
  ) => {
    if (busyAction) return
    setConfirmation({ key, title, description, execute })
  }

  const executeConfirmed = async () => {
    if (!confirmation || busyAction) return
    const action = confirmation
    setConfirmation(null)
    setBusyAction(action.key)
    setPageError('')
    setNotice('')
    try {
      await action.execute()
    } catch (error) {
      setPageError(error instanceof Error ? error.message : '관리자 작업에 실패했습니다.')
    } finally {
      setBusyAction(null)
    }
  }

  const syncGameData = () => requestConfirmation(
    'games-sync',
    '경기 데이터를 동기화하시겠습니까?',
    `${selectedDate} KBO 일정·상태·점수 데이터를 갱신하고 종료 경기는 자동 정산합니다.`,
    async () => {
      const result = await requestJson<GameSyncResponse>(
        `/api/admin/data/games/sync?date=${selectedDate}`,
        { method: 'POST' },
      )
      setGameSync(result)
      setNotice('경기 데이터 동기화가 완료되었습니다.')
      await Promise.all([loadGames(), loadSummary()])
    },
  )

  const syncTeamStats = () => requestConfirmation(
    'team-stats-sync',
    '오늘 팀 통계를 동기화하시겠습니까?',
    'KBO 공식 팀 성적을 오늘 기준 스냅샷으로 저장합니다.',
    async () => {
      setTeamStatsSync(await requestJson<TeamStatsSyncResponse>(
        '/api/admin/data/team-stats/sync', { method: 'POST' },
      ))
      setNotice('팀 통계 동기화가 완료되었습니다.')
    },
  )

  const syncPitchers = () => requestConfirmation(
    'pitcher-sync',
    '선발투수 데이터를 동기화하시겠습니까?',
    `${selectedDate} 경기의 선발투수와 투수 통계를 갱신합니다.`,
    async () => {
      setPitcherSync(await requestJson<StartingPitcherSyncResponse>(
        `/api/admin/data/starting-pitchers/sync?date=${selectedDate}`,
        { method: 'POST' },
      ))
      setNotice('선발투수 동기화가 완료되었습니다.')
      await loadGames()
    },
  )

  const generateDatePredictions = () => requestConfirmation(
    'prediction-date',
    '해당 날짜 AI 예측을 생성하시겠습니까?',
    '예측 마감 전 경기만 생성·갱신되며, 서버의 마감 정책은 우회하지 않습니다.',
    async () => {
      setPredictionBatch(await requestJson<PredictionGenerationBatchResponse>(
        `/api/admin/predictions/generate?date=${selectedDate}`,
        { method: 'POST' },
      ))
      setNotice('날짜별 AI 예측 생성 작업이 완료되었습니다.')
      await Promise.all([loadGames(), loadSummary()])
    },
  )

  const generateGamePrediction = (game: AdminGameResponse) => requestConfirmation(
    `prediction-${game.id}`,
    `${game.awayTeamName} vs ${game.homeTeamName} 예측을 생성하시겠습니까?`,
    `마감 시각 ${formatDateTime(game.predictionCloseAt)} 이후에는 서버가 변경을 차단합니다.`,
    async () => {
      setSinglePrediction(await requestJson<PredictionGenerationResponse>(
        `/api/admin/predictions/generate?gameId=${game.id}`,
        { method: 'POST' },
      ))
      setNotice('경기별 AI 예측 요청이 완료되었습니다.')
      await Promise.all([loadGames(), loadSummary()])
    },
  )

  const compareModels = async (gameId: number) => {
    if (busyAction) return
    try {
      setBusyAction(`compare-${gameId}`)
      setPageError('')
      setComparison(await requestJson<GameModelComparisonResponse>(
        `/api/admin/predictions/models/comparison/${gameId}`,
      ))
    } catch (error) {
      setPageError(error instanceof Error ? error.message : '모델 비교 조회에 실패했습니다.')
    } finally {
      setBusyAction(null)
    }
  }

  const runBackfill = () => requestConfirmation(
    'backfill',
    'Historical Backfill을 실행하시겠습니까?',
    `${backfillFrom}부터 ${backfillTo}까지 Feature snapshot과 예측 history를 생성합니다.${syncGamesForBackfill ? ' 외부 경기 동기화도 포함합니다.' : ''}`,
    async () => {
      setBackfill(await requestJson<BackfillResponse>(
        `/api/admin/predictions/backfill?from=${backfillFrom}&to=${backfillTo}&syncGames=${syncGamesForBackfill}`,
        { method: 'POST' },
      ))
      setNotice('Historical Backfill이 완료되었습니다.')
    },
  )

  const evaluateHistorical = async () => {
    if (busyAction) return
    try {
      setBusyAction('historical-evaluation')
      setPageError('')
      setHistoricalEvaluation(await requestJson<HistoricalEvaluationResponse>(
        `/api/admin/predictions/evaluation?from=${backfillFrom}&to=${backfillTo}&modelVersion=baseline-v1`,
      ))
    } catch (error) {
      setPageError(error instanceof Error ? error.message : 'Backtest 평가 조회에 실패했습니다.')
    } finally {
      setBusyAction(null)
    }
  }

  if (authLoading) {
    return <div className="min-h-screen bg-background"><AppHeader /><main className="mx-auto max-w-7xl px-4 py-16 text-center text-muted-foreground">권한을 확인하는 중입니다.</main></div>
  }

  if (!user || !isAdmin) {
    return (
      <div className="min-h-screen bg-background">
        <AppHeader />
        <main className="mx-auto flex max-w-xl flex-col items-center gap-4 px-4 py-20 text-center">
          <div className="flex size-14 items-center justify-center rounded-2xl bg-destructive/10 text-destructive"><LockKeyhole /></div>
          <h1 className="text-2xl font-black">관리자 접근 권한이 없습니다</h1>
          <p className="text-sm text-muted-foreground">
            {user ? 'ROLE_ADMIN 계정만 운영 콘솔을 사용할 수 있습니다.' : '로그인 후 관리자 권한을 확인해 주세요.'}
          </p>
          <Link to="/" className={cn(buttonVariants())}>메인으로 돌아가기</Link>
        </main>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-muted/20 text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-7xl flex-col gap-7 px-4 py-7 lg:px-6 lg:py-10">
        <section className="rounded-2xl bg-slate-950 p-6 text-white shadow-lg md:p-8">
          <div className="flex flex-col justify-between gap-5 md:flex-row md:items-end">
            <div>
              <Badge className="mb-3 bg-amber-400 text-slate-950 hover:bg-amber-400"><ShieldCheck data-icon="inline-start" /> ROLE_ADMIN 운영 콘솔</Badge>
              <h1 className="text-3xl font-black tracking-tight">PLAYBALL 관리자 대시보드</h1>
              <p className="mt-2 max-w-2xl text-sm text-slate-300">KBO 데이터 수집, 시스템 예측, Shadow 성능과 Historical Backtest를 한 화면에서 점검합니다.</p>
            </div>
            <div className="rounded-xl border border-white/15 bg-white/5 px-4 py-3 text-sm">
              <p className="text-slate-400">운영 기준일</p>
              <p className="mt-1 font-mono font-bold">{summary?.date ?? today}</p>
            </div>
          </div>
        </section>

        {pageError && <div className="flex items-start gap-2 rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive"><XCircle className="mt-0.5 size-4 shrink-0" />{pageError}</div>}
        {notice && <div className="flex items-start gap-2 rounded-xl border border-primary/30 bg-primary/5 p-4 text-sm text-primary"><CheckCircle2 className="mt-0.5 size-4 shrink-0" />{notice}</div>}

        <section>
          <div className="mb-4 flex items-end justify-between">
            <div><p className="text-xs font-bold uppercase tracking-wider text-primary">Operations</p><h2 className="text-xl font-black">오늘 운영 요약</h2></div>
            <Button variant="outline" size="sm" disabled={isLoading} onClick={() => void refreshOverview()}><RefreshCw data-icon="inline-start" /> 새로고침</Button>
          </div>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <SummaryCard label="오늘 경기" value={summary?.totalGameCount ?? 0} icon={<CalendarClock />} />
            <SummaryCard label="예정 경기" value={summary?.scheduledGameCount ?? 0} icon={<CalendarClock />} />
            <SummaryCard label="진행 중" value={summary?.inProgressGameCount ?? 0} icon={<Activity />} />
            <SummaryCard label="종료 경기" value={summary?.finishedGameCount ?? 0} icon={<CheckCircle2 />} />
            <SummaryCard label="취소 경기" value={summary?.cancelledGameCount ?? 0} icon={<XCircle />} />
            <SummaryCard label="시스템 예측" value={summary?.systemPredictionCount ?? 0} icon={<Bot />} />
            <SummaryCard label="Shadow 예측 경기" value={summary?.shadowPredictionGameCount ?? 0} icon={<Swords />} />
            <SummaryCard label="미정산 사용자 예측" value={summary?.pendingUserPredictionCount ?? 0} icon={<Users />} />
          </div>
        </section>

        <section className="grid gap-5 xl:grid-cols-[1.1fr_0.9fr]">
          <Card>
            <CardHeader><CardTitle className="flex items-center gap-2"><Database className="size-5" /> KBO 데이터 관리</CardTitle><CardDescription>DB를 갱신하는 작업입니다. 확인 후 한 번만 실행됩니다.</CardDescription></CardHeader>
            <CardContent className="grid gap-4">
              <label className="grid gap-1 text-xs font-semibold">대상 경기 날짜<input type="date" className="h-9 rounded-md border bg-background px-3 text-sm font-normal" value={selectedDate} onChange={(event) => setSelectedDate(event.target.value)} /></label>
              <div className="grid gap-2 sm:grid-cols-3">
                <Button disabled={busyAction !== null} onClick={syncGameData}>{busyAction === 'games-sync' ? '동기화 중...' : '경기 데이터 동기화'}</Button>
                <Button variant="outline" disabled={busyAction !== null} onClick={syncTeamStats}>{busyAction === 'team-stats-sync' ? '동기화 중...' : '팀 통계 동기화'}</Button>
                <Button variant="outline" disabled={busyAction !== null} onClick={syncPitchers}>{busyAction === 'pitcher-sync' ? '동기화 중...' : '선발투수 동기화'}</Button>
              </div>
              {gameSync && <div className="grid gap-3 rounded-xl border p-4"><p className="font-semibold">경기 동기화 결과 · {gameSync.targetDate}</p><ResultMetrics values={[["원본 경기", gameSync.sourceRowCount], ["INSERT", gameSync.insertedCount], ["UPDATE", gameSync.updatedCount], ["상태 변경", gameSync.statusChangedCount], ["FINISHED", gameSync.finishedCount], ["CANCELLED", gameSync.cancelledCount], ["자동 정산", gameSync.settlementSuccessCount], ["실패", gameSync.failedCount]]} /><ErrorList errors={gameSync.errors} /></div>}
              {teamStatsSync && <div className="grid gap-3 rounded-xl border p-4"><p className="font-semibold">팀 통계 결과 · {teamStatsSync.statDate}</p><ResultMetrics values={[["원본 팀", teamStatsSync.sourceTeamCount], ["INSERT", teamStatsSync.insertedCount], ["UPDATE", teamStatsSync.updatedCount], ["실패", teamStatsSync.failedCount]]} /><ErrorList errors={teamStatsSync.errors} /></div>}
              {pitcherSync && <div className="grid gap-3 rounded-xl border p-4"><p className="font-semibold">선발투수 결과 · {pitcherSync.gameDate}</p><ResultMetrics values={[["원본 경기", pitcherSync.sourceGameCount], ["수집 투수", pitcherSync.collectedPitcherCount], ["INSERT", pitcherSync.insertedCount], ["UPDATE", pitcherSync.updatedCount], ["투수 기록", pitcherSync.pitcherStatSavedCount], ["실패", pitcherSync.failedCount]]} /><ErrorList errors={pitcherSync.errors} /></div>}
            </CardContent>
          </Card>

          <Card>
            <CardHeader><CardTitle className="flex items-center gap-2"><ServerCog className="size-5" /> 예측 모델 상태</CardTitle><CardDescription>모델 전환은 배포 설정으로만 수행할 수 있습니다.</CardDescription></CardHeader>
            <CardContent className="grid gap-3">
              <div className="rounded-xl border border-primary/30 bg-primary/5 p-4"><p className="text-xs font-bold uppercase tracking-wider text-primary">Production</p><p className="mt-1 font-mono text-xl font-black">{summary?.productionModelVersion ?? 'baseline-v1'}</p><p className="mt-1 text-xs text-muted-foreground">사용자 대시보드 시스템 예측</p></div>
              <div className="rounded-xl border border-violet-400/30 bg-violet-500/5 p-4"><p className="text-xs font-bold uppercase tracking-wider text-violet-600">Shadow</p><p className="mt-1 font-mono text-xl font-black">{summary?.shadowModelVersion ?? 'logistic-v1'}</p><p className="mt-2 break-all font-mono text-[10px] text-muted-foreground">SHA-256 {summary?.shadowArtifactSha256 ?? '-'}</p></div>
              <div className="rounded-lg bg-muted p-3 text-xs text-muted-foreground"><AlertTriangle className="mr-1 inline size-3.5" />이 화면에서는 production 모델을 변경할 수 없습니다.</div>
            </CardContent>
          </Card>
        </section>

        <Card>
          <CardHeader><CardTitle>날짜별 경기 관리</CardTitle><CardDescription>경기 조회는 기존 `/api/games` 응답을 사용합니다.</CardDescription></CardHeader>
          <CardContent className="grid gap-4">
            <div className="flex flex-wrap items-center gap-2"><Button disabled={busyAction !== null} onClick={generateDatePredictions}>{busyAction === 'prediction-date' ? '생성 중...' : `${selectedDate} 전체 AI 예측 생성`}</Button>{predictionBatch && <p className="text-xs text-muted-foreground">대상 {predictionBatch.targetCount} · 생성 {predictionBatch.createdCount} · 갱신 {predictionBatch.updatedCount} · 건너뜀 {predictionBatch.skippedCount} · 실패 {predictionBatch.failedCount}</p>}{singlePrediction && <p className="text-xs text-muted-foreground">최근 경기 요청: #{singlePrediction.gameId} {singlePrediction.status}</p>}</div>
            {games.length === 0 ? <p className="rounded-xl border py-10 text-center text-sm text-muted-foreground">선택한 날짜의 경기가 없습니다.</p> : (
              <div className="overflow-x-auto rounded-xl border">
                <table className="w-full min-w-[980px] text-left text-sm">
                  <thead className="bg-muted/70 text-xs text-muted-foreground"><tr><th className="px-4 py-3">경기</th><th className="px-4 py-3">시간/상태</th><th className="px-4 py-3">점수</th><th className="px-4 py-3">예측 마감</th><th className="px-4 py-3">시스템 예측</th><th className="px-4 py-3">최종 배당</th><th className="px-4 py-3">작업</th></tr></thead>
                  <tbody>{games.map((game) => <tr key={game.id} className="border-t"><td className="px-4 py-3 font-semibold">{game.awayTeamName} vs {game.homeTeamName}<p className="text-xs font-normal text-muted-foreground">#{game.id} · {game.stadium}</p></td><td className="px-4 py-3"><span className="font-mono">{game.gameTime.slice(0, 5)}</span><Badge className="ml-2" variant={statusVariants[game.status]}>{statusLabels[game.status]}</Badge></td><td className="px-4 py-3 font-mono">{game.awayScore ?? '-'} : {game.homeScore ?? '-'}</td><td className="px-4 py-3 text-xs">{formatDateTime(game.predictionCloseAt)}</td><td className="px-4 py-3">{game.aiPrediction ? <Badge variant="secondary">{game.aiPrediction.modelVersion}</Badge> : <Badge variant="outline">미생성</Badge>}</td><td className="px-4 py-3">{game.userOdds?.finalized ? <Badge>확정</Badge> : <Badge variant="outline">실시간</Badge>}</td><td className="px-4 py-3"><div className="flex gap-1"><Button size="xs" variant="outline" disabled={busyAction !== null} onClick={() => generateGamePrediction(game)}>AI 생성</Button><Button size="xs" variant="ghost" disabled={busyAction !== null} onClick={() => void compareModels(game.id)}>모델 비교</Button></div></td></tr>)}</tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>

        {comparison && <Card><CardHeader><CardTitle>경기별 모델 비교 · #{comparison.gameId}</CardTitle><CardDescription>{comparison.awayTeamName} vs {comparison.homeTeamName} · 실제 결과 {comparison.actualResult ?? '미확정'} · 동일 Feature {comparison.sameFeatureSnapshot ? '확인' : '불일치/없음'}</CardDescription></CardHeader><CardContent className="grid gap-4 md:grid-cols-2">{([['baseline-v1', comparison.baseline], ['logistic-v1', comparison.logistic]] as const).map(([label, prediction]) => <div key={label} className="rounded-xl border p-4"><p className="font-mono text-sm font-black">{label}</p>{prediction ? <><div className="mt-3 grid grid-cols-3 gap-2 text-center"><div><p className="font-mono text-xl font-bold">{prediction.homeWinProbability}%</p><p className="text-xs text-muted-foreground">홈 승</p></div><div><p className="font-mono text-xl font-bold">{prediction.drawProbability}%</p><p className="text-xs text-muted-foreground">무승부</p></div><div><p className="font-mono text-xl font-bold">{prediction.awayWinProbability}%</p><p className="text-xs text-muted-foreground">원정 승</p></div></div><Separator className="my-3" /><p className="font-mono text-[10px] text-muted-foreground">snapshot #{prediction.featureSnapshotId ?? '-'} · feature {formatDateTime(prediction.featureAsOf)} · generated {formatDateTime(prediction.generatedAt)}</p></> : <p className="mt-4 text-sm text-muted-foreground">저장된 예측이 없습니다.</p>}</div>)}</CardContent></Card>}

        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2"><BarChart3 className="size-5" /> Shadow 성능</CardTitle><CardDescription>동일 Feature snapshot을 사용한 FINAL 경기만 공정하게 비교합니다.</CardDescription></CardHeader>
          <CardContent className="grid gap-4">
            <div className="flex flex-wrap items-end gap-2"><label className="grid gap-1 text-xs font-semibold">시작일<input type="date" className="h-9 rounded-md border bg-background px-3 text-sm font-normal" value={shadowFrom} onChange={(event) => setShadowFrom(event.target.value)} /></label><label className="grid gap-1 text-xs font-semibold">종료일<input type="date" className="h-9 rounded-md border bg-background px-3 text-sm font-normal" value={shadowTo} onChange={(event) => setShadowTo(event.target.value)} /></label><Button variant="outline" disabled={shadowLoading} onClick={() => void loadShadow()}>{shadowLoading ? '조회 중...' : 'Shadow 평가 조회'}</Button></div>
            {!shadow ? <div className="rounded-xl border border-dashed py-10 text-center"><Bot className="mx-auto mb-3 text-muted-foreground" /><p className="font-semibold">기간을 선택해 운영 Shadow 평가를 조회하세요.</p></div> : shadow.commonEvaluatedGameCount === 0 ? <div className="rounded-xl border border-dashed py-10 text-center"><Bot className="mx-auto mb-3 text-muted-foreground" /><p className="font-semibold">공통 운영 FINAL 평가 경기 0건</p><p className="mt-1 text-sm text-muted-foreground">baseline FINAL {shadow.baselineEligibleFinalGameCount} · logistic FINAL {shadow.logisticEligibleFinalGameCount} · snapshot 불일치 {shadow.featureSnapshotMismatchCount} · 비운영 snapshot {shadow.nonOperationalSnapshotCount} · 경기 시작 시각 위반 {shadow.pregameCutoffViolationCount} · artifact 불일치 {shadow.artifactMismatchCount}</p></div> : <>
              <div className="flex flex-wrap items-center gap-2 text-sm"><span>공통 운영 FINAL <strong className="font-mono">{shadow.commonEvaluatedGameCount}</strong>경기</span><Badge variant={shadow.sampleSizeAssessment.advisoryPromotionSampleSizeReached ? 'secondary' : 'outline'}>{shadow.sampleSizeAssessment.advisoryPromotionSampleSizeReached ? '표본 크기 gate 충족' : '표본 수집 중'}</Badge><span className="text-xs text-muted-foreground">HOME {shadow.sampleSizeAssessment.homeWinCount} · DRAW {shadow.sampleSizeAssessment.drawCount} · AWAY {shadow.sampleSizeAssessment.awayWinCount}</span></div>
              <div className="overflow-x-auto rounded-xl border"><table className="w-full min-w-[760px] text-sm"><thead className="bg-muted/70"><tr><th className="px-4 py-3 text-left">지표</th><th className="px-4 py-3 text-right">baseline-v1</th><th className="px-4 py-3 text-right">logistic-v1</th><th className="px-4 py-3 text-right">logistic-baseline / paired 95% CI</th></tr></thead><tbody>{[
                ['Accuracy', percent(shadow.baseline.accuracy), percent(shadow.logistic.accuracy), signedMetric(shadow.pairedMetrics.accuracy.logisticMinusBaseline, true), confidenceInterval(shadow.pairedMetrics.accuracy.bootstrap95Lower, shadow.pairedMetrics.accuracy.bootstrap95Upper, true)],
                ['Log Loss', metric(shadow.baseline.logLoss), metric(shadow.logistic.logLoss), signedMetric(shadow.pairedMetrics.logLoss.logisticMinusBaseline), confidenceInterval(shadow.pairedMetrics.logLoss.bootstrap95Lower, shadow.pairedMetrics.logLoss.bootstrap95Upper)],
                ['Brier', metric(shadow.baseline.brierScore), metric(shadow.logistic.brierScore), signedMetric(shadow.pairedMetrics.brierScore.logisticMinusBaseline), confidenceInterval(shadow.pairedMetrics.brierScore.bootstrap95Lower, shadow.pairedMetrics.brierScore.bootstrap95Upper)],
              ].map(([label, baseline, logistic, difference, interval]) => <tr key={label} className="border-t"><td className="px-4 py-3 font-semibold">{label}</td><td className="px-4 py-3 text-right font-mono">{baseline}</td><td className="px-4 py-3 text-right font-mono">{logistic}</td><td className="px-4 py-3 text-right font-mono">{difference} · {interval}</td></tr>)}</tbody></table></div>
              <div className="overflow-x-auto rounded-xl border"><table className="w-full min-w-[760px] text-sm"><thead className="bg-muted/70"><tr><th className="px-4 py-3 text-left">Class</th><th className="px-4 py-3 text-right">실제 발생률</th><th className="px-4 py-3 text-right">baseline 평균 / ECE</th><th className="px-4 py-3 text-right">logistic 평균 / ECE</th></tr></thead><tbody>{(['HOME_WIN', 'DRAW', 'AWAY_WIN'] as const).map((outcome) => <tr key={outcome} className="border-t"><td className="px-4 py-3 font-semibold">{outcome}</td><td className="px-4 py-3 text-right font-mono">{percent(shadow.actualOutcomeRates[outcome])}</td><td className="px-4 py-3 text-right font-mono">{percent(shadow.baseline.averageProbabilities[outcome])} / {percent(shadow.baseline.calibration[outcome].expectedCalibrationError)}</td><td className="px-4 py-3 text-right font-mono">{percent(shadow.logistic.averageProbabilities[outcome])} / {percent(shadow.logistic.calibration[outcome].expectedCalibrationError)}</td></tr>)}</tbody></table></div>
              <ResultMetrics values={[["logistic만 적중", shadow.logisticCorrectBaselineWrongCount], ["baseline만 적중", shadow.baselineCorrectLogisticWrongCount], ["둘 다 적중", shadow.bothCorrectCount], ["둘 다 실패", shadow.bothWrongCount]]} />
              <p className="text-xs text-muted-foreground">예측 일치율 {percent(shadow.predictedOutcomeAgreementRate)} · baseline FINAL {shadow.baselineEligibleFinalGameCount} · logistic FINAL {shadow.logisticEligibleFinalGameCount} · snapshot 불일치 {shadow.featureSnapshotMismatchCount} · 비운영 snapshot {shadow.nonOperationalSnapshotCount} · 경기 시작 시각 위반 {shadow.pregameCutoffViolationCount} · artifact 불일치 {shadow.artifactMismatchCount}</p>
              <p className="text-xs text-muted-foreground">Artifact SHA-256 <span className="font-mono">{shadow.logisticArtifactSha256}</span> · 승격 검토 표본 gate까지 공통 경기 {shadow.sampleSizeAssessment.additionalCommonGamesNeeded}건, DRAW {shadow.sampleSizeAssessment.additionalDrawsNeeded}건 추가 필요</p>
            </>}
          </CardContent>
        </Card>

        <Card className="border-amber-400/40">
          <CardHeader><CardTitle className="flex items-center gap-2"><AlertTriangle className="size-5 text-amber-600" /> Historical / Backtest</CardTitle><CardDescription>페이지 진입만으로 실행되지 않습니다. Backfill은 데이터 양에 따라 오래 걸릴 수 있습니다.</CardDescription></CardHeader>
          <CardContent className="grid gap-4">
            <div className="flex flex-wrap items-end gap-2"><label className="grid gap-1 text-xs font-semibold">시작일<input type="date" className="h-9 rounded-md border bg-background px-3 text-sm font-normal" value={backfillFrom} onChange={(event) => setBackfillFrom(event.target.value)} /></label><label className="grid gap-1 text-xs font-semibold">종료일<input type="date" className="h-9 rounded-md border bg-background px-3 text-sm font-normal" value={backfillTo} onChange={(event) => setBackfillTo(event.target.value)} /></label><label className="flex h-9 items-center gap-2 rounded-md border px-3 text-xs"><input type="checkbox" checked={syncGamesForBackfill} onChange={(event) => setSyncGamesForBackfill(event.target.checked)} />외부 경기 동기화 포함</label><Button variant="destructive" disabled={busyAction !== null} onClick={runBackfill}>{busyAction === 'backfill' ? 'Backfill 실행 중...' : 'Backfill 실행'}</Button><Button variant="outline" disabled={busyAction !== null} onClick={() => void evaluateHistorical()}>{busyAction === 'historical-evaluation' ? '평가 중...' : 'baseline-v1 평가 조회'}</Button></div>
            {backfill && <div className="grid gap-3 rounded-xl border p-4"><p className="font-semibold">Backfill 결과 · {backfill.from} ~ {backfill.to}</p><ResultMetrics values={[["종료 경기", backfill.finishedGameCount], ["Snapshot 생성", backfill.snapshotCreatedCount], ["Snapshot 기존", backfill.snapshotExistingCount], ["History 생성", backfill.historyCreatedCount], ["History 기존", backfill.historyExistingCount], ["실패 경기", backfill.failedGameCount]]} /><ErrorList errors={[...backfill.errors, ...backfill.gameSync.errors]} /></div>}
            {historicalEvaluation && <div className="grid gap-3 rounded-xl border p-4"><p className="font-semibold">Historical 평가 · {historicalEvaluation.modelVersion}</p><ResultMetrics values={[["종료 경기", historicalEvaluation.finishedGameCount], ["Feature 생성", historicalEvaluation.featureGeneratedGameCount], ["평가 가능", historicalEvaluation.evaluableGameCount]]} /><div className="grid grid-cols-2 gap-2 sm:grid-cols-4"><div className="rounded-lg bg-muted p-3"><p className="text-xs text-muted-foreground">Coverage</p><p className="font-mono font-bold">{percent(historicalEvaluation.dataCoverage / 100)}</p></div><div className="rounded-lg bg-muted p-3"><p className="text-xs text-muted-foreground">Accuracy</p><p className="font-mono font-bold">{percent(historicalEvaluation.overallAccuracy / 100)}</p></div><div className="rounded-lg bg-muted p-3"><p className="text-xs text-muted-foreground">Log Loss</p><p className="font-mono font-bold">{metric(historicalEvaluation.logLoss)}</p></div><div className="rounded-lg bg-muted p-3"><p className="text-xs text-muted-foreground">Brier</p><p className="font-mono font-bold">{metric(historicalEvaluation.brierScore)}</p></div></div></div>}
          </CardContent>
        </Card>
      </main>

      {confirmation && <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4" role="dialog" aria-modal="true" aria-labelledby="admin-confirm-title"><Card className="w-full max-w-md"><CardHeader><CardTitle id="admin-confirm-title">{confirmation.title}</CardTitle><CardDescription>{confirmation.description}</CardDescription></CardHeader><CardContent className="flex justify-end gap-2"><Button variant="outline" onClick={() => setConfirmation(null)}>취소</Button><Button variant="destructive" onClick={() => void executeConfirmed()}>실행</Button></CardContent></Card></div>}
    </div>
  )
}
