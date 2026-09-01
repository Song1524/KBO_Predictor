import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
} from 'react'
import {
  ChevronLeft,
  ChevronRight,
  MessageCircle,
  Sparkles,
  Trophy,
  Users,
} from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth-context'
import { AppHeader } from '@/components/app-header'
import { GamePredictionPanel } from '@/components/game-prediction-panel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import type { UserPredictionApiResponse } from '@/lib/api-types'
import { apiFetch } from '@/lib/api-client'
import type {
  GameApiResponse,
  GameOddsApiResponse,
  SystemPredictionApiResponse,
} from '@/lib/game-api-types'
import { useStandings } from '@/lib/use-standings'

const GAMES_POLLING_INTERVAL_MS = 30_000

type DashboardGame = {
  id: number
  gameDate: string
  predictionCloseAt: string | null
  awayTeamId: number | null
  homeTeamId: number | null
  time: string
  stadium: string
  status: GameApiResponse['status']
  away: string
  home: string
  aiPrediction: SystemPredictionApiResponse | null
  userOdds: GameOddsApiResponse | null
  awayScore: number | null
  homeScore: number | null
  awayStartingPitcherName: string | null
  homeStartingPitcherName: string | null
  cancelReason: string | null
}

type GamesLoadMode = 'initial' | 'refresh'

type ActiveGamesRequest = {
  date: string
  controller: AbortController
}

function getKoreaDate(date = new Date()) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date)
  const values = Object.fromEntries(
    parts.map(({ type, value }) => [type, value]),
  )

  return `${values.year}-${values.month}-${values.day}`
}

function shouldPollGames(date: string, games: DashboardGame[]) {
  if (date >= getKoreaDate() || games.length === 0) return true

  return games.some(
    (game) => game.status !== 'FINISHED' && game.status !== 'CANCELLED',
  )
}

function moveDate(date: string, days: number) {
  const [year, month, day] = date.split('-').map(Number)
  const movedDate = new Date(Date.UTC(year, month - 1, day + days))
  return movedDate.toISOString().slice(0, 10)
}

function formatSelectedDate(date: string) {
  return new Date(`${date}T00:00:00+09:00`).toLocaleDateString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'long',
  })
}

function normalizeText(value: string | null, fallback: string) {
  const normalized = value?.trim()
  return normalized || fallback
}

function normalizeNullableText(value: string | null) {
  const normalized = value?.trim()
  return normalized || null
}

function getTeamMark(teamName: string) {
  return teamName === '정보 없음'
    ? '-'
    : teamName.split(/\s+/)[0].slice(0, 3).toUpperCase()
}

function getGameStatusLabel(status: DashboardGame['status']) {
  switch (status) {
    case 'SCHEDULED':
      return '경기 전'
    case 'IN_PROGRESS':
      return '경기 중'
    case 'FINISHED':
      return '경기 종료'
    case 'CANCELLED':
      return '경기 취소'
    default:
      return '정보 없음'
  }
}

function getAiPredictionLabel(game: DashboardGame) {
  const prediction = game.aiPrediction
  if (!prediction?.predictedOutcome) return null

  switch (prediction.predictedOutcome) {
    case 'HOME_WIN':
      return `${normalizeNullableText(prediction.predictedWinnerTeamName) ?? game.home} 승리`
    case 'DRAW':
      return '무승부'
    case 'AWAY_WIN':
      return `${normalizeNullableText(prediction.predictedWinnerTeamName) ?? game.away} 승리`
  }
}

function getPredictionReasons(reason: string | null) {
  if (!reason) return []

  return reason
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, 2)
}

function probabilityBarWidth(value: number) {
  return `${Math.min(100, Math.max(0, value))}%`
}

function formatProbability(value: number | null) {
  return value == null || !Number.isFinite(value)
    ? '-'
    : `${value.toFixed(1)}%`
}

function formatStandingWinRate(value: number | null) {
  if (value == null) return '-'
  return Number(value).toFixed(3).replace(/^0/, '')
}

function formatGamesBehind(value: number | null) {
  if (value == null) return '-'
  return Number(value) === 0 ? '0' : Number(value).toFixed(1)
}

function toFiniteNumber(value: number | null | undefined) {
  if (value == null) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function formatTotalBetPoints(value: number | null) {
  return value == null || !Number.isFinite(Number(value))
    ? '투표 정보 없음'
    : `${Number(value).toLocaleString()}P 참여`
}

function TeamMark({ teamName }: { teamName: string }) {
  return (
    <div className="flex size-12 items-center justify-center rounded-full bg-primary font-mono text-sm font-black text-primary-foreground">
      {getTeamMark(teamName)}
    </div>
  )
}

function AiPredictionPanel({ game }: { game: DashboardGame }) {
  const prediction = game.aiPrediction
  if (!prediction) {
    return (
      <div className="flex items-center gap-2 border-t pt-3 text-sm font-semibold text-muted-foreground">
        <Sparkles className="size-4" />
        AI 분석 준비 중
      </div>
    )
  }

  const awayProbability = toFiniteNumber(prediction.awayWinProbability)
  const drawProbability = toFiniteNumber(prediction.drawProbability)
  const homeProbability = toFiniteNumber(prediction.homeWinProbability)
  const probabilities =
    awayProbability != null &&
    drawProbability != null &&
    homeProbability != null
      ? [
          {
            outcome: 'AWAY_WIN' as const,
            label: '원정',
            value: awayProbability,
            barClassName: 'bg-accent',
          },
          {
            outcome: 'DRAW' as const,
            label: '무승부',
            value: drawProbability,
            barClassName: 'bg-muted-foreground/50',
          },
          {
            outcome: 'HOME_WIN' as const,
            label: '홈',
            value: homeProbability,
            barClassName: 'bg-primary',
          },
        ]
      : null
  const predictionLabel = getAiPredictionLabel(game)
  const reasons = getPredictionReasons(prediction.reason)

  return (
    <section className="border-t pt-3">
      <div className="flex min-w-0 items-start gap-2">
        <div className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
          <Sparkles className="size-3.5" />
        </div>
        <div className="min-w-0">
          <p className="text-[11px] font-bold uppercase tracking-wide text-primary">
            AI 예상
          </p>
          <p
            className="line-clamp-1 text-sm font-black"
            title={predictionLabel ?? undefined}
          >
            {predictionLabel ?? '예상 결과 확인 중'}
          </p>
        </div>
      </div>

      {probabilities ? (
        <>
          <div
            className="mt-3 flex h-2 overflow-hidden rounded-full bg-border"
            aria-label={probabilities
              .map(({ label, value }) => `${label} ${formatProbability(value)}`)
              .join(', ')}
            role="img"
          >
            {probabilities.map(({ outcome, value, barClassName }) => (
              <span
                key={outcome}
                className={`${barClassName} ${prediction.predictedOutcome === outcome ? 'opacity-100' : 'opacity-50'}`}
                style={{ width: probabilityBarWidth(value) }}
              />
            ))}
          </div>
          <div className="mt-1.5 grid grid-cols-3 text-center text-[11px]">
            {probabilities.map(({ outcome, label, value }) => {
              const predicted = prediction.predictedOutcome === outcome
              return (
                <p
                  key={outcome}
                  className={predicted
                    ? 'font-bold text-primary'
                    : 'text-muted-foreground'}
                >
                  <span>{label}</span>{' '}
                  <span className="font-mono">{formatProbability(value)}</span>
                </p>
              )
            })}
          </div>
        </>
      ) : (
        <p className="mt-2 text-xs font-medium text-muted-foreground">
          AI 확률 데이터 준비 중
        </p>
      )}

      {reasons.length > 0 && (
        <div className="mt-2.5">
          <p className="text-[11px] font-bold text-foreground/70">
            예측 근거
          </p>
          <ul className="mt-1 grid gap-1">
            {reasons.map((reason, index) => (
              <li
                key={`${index}-${reason}`}
                className="flex min-w-0 gap-1.5 text-[11px] leading-relaxed text-muted-foreground"
                title={reason}
              >
                <span aria-hidden="true" className="text-primary">•</span>
                <span className="line-clamp-1">{reason}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

function GameCard({
  game,
  existingPrediction,
  onPredictionCreated,
}: {
  game: DashboardGame
  existingPrediction: UserPredictionApiResponse | null
  onPredictionCreated: () => void
}) {
  const navigate = useNavigate()
  const showScore =
    game.status === 'IN_PROGRESS' || game.status === 'FINISHED'
  const detailPath = '/games/' + game.id

  const isPredictionControl = (target: EventTarget | null) =>
    target instanceof Element &&
    target.closest(
      '[data-prevent-card-navigation], a, button, input, select, textarea, label',
    ) !== null

  const openDetail = (event: MouseEvent<HTMLDivElement>) => {
    if (isPredictionControl(event.target)) return
    navigate(detailPath)
  }

  const openDetailFromKeyboard = (event: KeyboardEvent<HTMLDivElement>) => {
    if (
      event.target !== event.currentTarget ||
      (event.key !== 'Enter' && event.key !== ' ')
    ) {
      return
    }

    event.preventDefault()
    navigate(detailPath)
  }

  return (
    <Card
      className="cursor-pointer transition-shadow hover:shadow-md focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
      role="link"
      tabIndex={0}
      aria-label={game.away + ' 대 ' + game.home + ' 경기 상세 보기'}
      onClick={openDetail}
      onKeyDown={openDetailFromKeyboard}
    >
      <CardHeader className="border-b">
        <CardTitle className="flex items-center gap-2">
          <span className="font-mono text-sm">{game.time}</span>
          <Badge variant="secondary">
            {getGameStatusLabel(game.status)}
          </Badge>
        </CardTitle>

        <CardDescription>
          {game.stadium}
          {game.status === 'CANCELLED' && game.cancelReason
            ? ' · ' + game.cancelReason
            : ''}
        </CardDescription>

        <CardAction>
          <div className="flex items-center gap-1 text-right">
            <span className="font-mono text-xs font-medium text-foreground/70">
              {game.userOdds
                ? formatTotalBetPoints(game.userOdds.totalBetPoints)
                : '투표 정보 없음'}
            </span>
            <ChevronRight className="size-3.5 text-muted-foreground" />
          </div>
        </CardAction>
      </CardHeader>

      <CardContent className="flex flex-col gap-4">
        <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-3">
          <div className="flex min-w-0 flex-col items-center gap-2 text-center">
            <TeamMark teamName={game.away} />
            <strong className="max-w-full truncate text-base">{game.away}</strong>
            <span
              className="max-w-full truncate text-xs font-medium text-foreground/70"
              title={game.awayStartingPitcherName ?? '선발 미정'}
            >
              선발 {game.awayStartingPitcherName ?? '미정'}
            </span>
          </div>

          <div
            className={showScore
              ? 'font-mono text-xl font-black tracking-tight'
              : 'font-mono text-xs font-bold text-muted-foreground'}
            aria-label={showScore
              ? [
                  game.away,
                  game.awayScore ?? '점수 미정',
                  game.home,
                  game.homeScore ?? '점수 미정',
                ].join(' ')
              : game.away + ' 대 ' + game.home}
          >
            {showScore
              ? [game.awayScore ?? '-', game.homeScore ?? '-'].join(' : ')
              : 'VS'}
          </div>

          <div className="flex min-w-0 flex-col items-center gap-2 text-center">
            <TeamMark teamName={game.home} />
            <strong className="max-w-full truncate text-base">{game.home}</strong>
            <span
              className="max-w-full truncate text-xs font-medium text-foreground/70"
              title={game.homeStartingPitcherName ?? '선발 미정'}
            >
              선발 {game.homeStartingPitcherName ?? '미정'}
            </span>
          </div>
        </div>

        <AiPredictionPanel game={game} />

        <div data-prevent-card-navigation>
          <GamePredictionPanel
            game={game}
            existingPrediction={existingPrediction}
            onPredictionCreated={onPredictionCreated}
          />
        </div>
      </CardContent>
    </Card>
  )
}

export function KboDashboard() {
  const { user, isLoading: isAuthLoading, refreshUser } = useAuth()
  const {
    standings,
    isLoading: isLoadingStandings,
    error: standingsError,
    reload: reloadStandings,
  } = useStandings()
  const [selectedDate, setSelectedDate] = useState(getKoreaDate)
  const [games, setGames] = useState<DashboardGame[]>([])
  const [isLoadingGames, setIsLoadingGames] = useState(true)
  const [gamesError, setGamesError] = useState('')
  const gamesRequestIdRef = useRef(0)
  const activeGamesRequestRef = useRef<ActiveGamesRequest | null>(null)
  const gamesPollingStateRef = useRef({
    date: selectedDate,
    enabled: true,
  })

  const [userPredictions, setUserPredictions] = useState<
      UserPredictionApiResponse[]
  >([])

  const loadUserPredictions = async () => {
    try {
      const response = await apiFetch(
          '/api/user-predictions/me',
          { credentials: 'include' },
      )

      if (response.status === 401) {
        setUserPredictions([])
        return
      }

      if (!response.ok) {
        throw new Error('승부예측 내역을 불러오지 못했습니다.')
      }

      const data: UserPredictionApiResponse[] =
          await response.json()

      setUserPredictions(data)
    } catch (error) {
      console.error(error)
      setUserPredictions([])
    }
  }

  const loadGames = useCallback(async (
    date: string,
    mode: GamesLoadMode = 'refresh',
  ) => {
    const activeRequest = activeGamesRequestRef.current
    if (
      activeRequest?.date === date &&
      !activeRequest.controller.signal.aborted
    ) {
      return
    }
    activeRequest?.controller.abort()

    const controller = new AbortController()
    activeGamesRequestRef.current = { date, controller }
    const requestId = ++gamesRequestIdRef.current

    try {
      if (mode === 'initial') {
        setIsLoadingGames(true)
        setGamesError('')
        setGames([])
      }

      const gameResponse = await apiFetch(
        `/api/games?date=${encodeURIComponent(date)}`,
        { signal: controller.signal },
      )
      if (!gameResponse.ok) {
        throw new Error('경기 정보를 불러오지 못했습니다.')
      }

      const gameData: unknown = await gameResponse.json()
      if (
        !Array.isArray(gameData) ||
        !gameData.every((game) =>
          typeof game === 'object' &&
          game !== null &&
          typeof (game as { id?: unknown }).id === 'number' &&
          Number.isFinite((game as { id: number }).id),
        )
      ) {
        throw new Error('경기 정보를 불러오지 못했습니다.')
      }

      const dashboardGames = (gameData as GameApiResponse[]).map(
        (game): DashboardGame => {
          return {
            id: game.id,
            gameDate: game.gameDate,
            predictionCloseAt: game.predictionCloseAt,
            awayTeamId: game.awayTeamId,
            homeTeamId: game.homeTeamId,
            time: normalizeText(game.gameTime?.slice(0, 5) ?? null, '-'),
            stadium: normalizeText(game.stadium, '구장 정보 없음'),
            status: game.status,
            away: normalizeText(game.awayTeamName, '정보 없음'),
            home: normalizeText(game.homeTeamName, '정보 없음'),
            aiPrediction: game.aiPrediction,
            userOdds: game.userOdds,
            awayScore: game.awayScore,
            homeScore: game.homeScore,
            awayStartingPitcherName:
              normalizeNullableText(game.awayStartingPitcherName),
            homeStartingPitcherName:
              normalizeNullableText(game.homeStartingPitcherName),
            cancelReason: game.cancelReason,
          }
        },
      )

      if (
        !controller.signal.aborted &&
        requestId === gamesRequestIdRef.current
      ) {
        setGames(dashboardGames)
        setGamesError('')
        gamesPollingStateRef.current = {
          date,
          enabled: shouldPollGames(date, dashboardGames),
        }
      }
    } catch (error) {
      if (controller.signal.aborted) return
      if (requestId === gamesRequestIdRef.current) {
        console.error(error)
        if (mode === 'initial') {
          setGames([])
          setGamesError('경기 정보를 불러오지 못했습니다.')
        }
      }
    } finally {
      if (activeGamesRequestRef.current?.controller === controller) {
        activeGamesRequestRef.current = null
      }
      if (
        mode === 'initial' &&
        requestId === gamesRequestIdRef.current &&
        !controller.signal.aborted
      ) {
        setIsLoadingGames(false)
      }
    }
  }, [])

  useEffect(() => {
    if (isAuthLoading) return

    if (user) {
      void loadUserPredictions()
    } else {
      setUserPredictions([])
    }
  }, [isAuthLoading, user?.id])

  useEffect(() => {
    gamesPollingStateRef.current = {
      date: selectedDate,
      enabled: true,
    }
    void loadGames(selectedDate, 'initial')

    const pollCurrentDate = () => {
      const pollingState = gamesPollingStateRef.current
      if (
        document.visibilityState !== 'visible' ||
        pollingState.date !== selectedDate ||
        !pollingState.enabled
      ) {
        return
      }

      void loadGames(selectedDate, 'refresh')
    }

    const intervalId = window.setInterval(
      pollCurrentDate,
      GAMES_POLLING_INTERVAL_MS,
    )
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') pollCurrentDate()
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)

    return () => {
      window.clearInterval(intervalId)
      document.removeEventListener(
        'visibilitychange',
        handleVisibilityChange,
      )
      const activeRequest = activeGamesRequestRef.current
      if (activeRequest?.date === selectedDate) {
        activeRequest.controller.abort()
      }
    }
  }, [loadGames, selectedDate])

  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-7xl flex-col gap-8 px-4 py-7 lg:px-6 lg:py-10">
        <section className="flex flex-col gap-5 rounded-2xl bg-primary p-6 text-primary-foreground md:p-8">
          <div className="flex max-w-2xl flex-col gap-3">
            <Badge variant="secondary" className="w-fit"><Sparkles data-icon="inline-start" />데이터 기반 KBO 승부예측</Badge>
            <h1 className="text-balance text-3xl font-black tracking-tight md:text-4xl">오늘의 승부, 당신의 선택은?</h1>
            <p className="max-w-xl text-pretty text-sm leading-relaxed text-primary-foreground/70 md:text-base">팀 기록과 선발 데이터를 바탕으로 계산한 예측을 확인하고 야구팬들과 함께 오늘의 승자를 맞혀보세요.</p>
          </div>
        </section>

        <section id="games" className="flex flex-col gap-5">
          <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-end">
            <div><p className="mb-1 text-sm font-semibold text-accent">{formatSelectedDate(selectedDate)}</p><h2 className="text-2xl font-black tracking-tight">선택 날짜의 경기</h2></div>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant="outline"
                size="icon"
                aria-label="이전 날짜"
                onClick={() => setSelectedDate((date) => moveDate(date, -1))}
              >
                <ChevronLeft />
              </Button>
              <input
                type="date"
                value={selectedDate}
                aria-label="경기 조회 날짜"
                onChange={(event) => {
                  if (event.target.value) setSelectedDate(event.target.value)
                }}
                className="h-9 rounded-md border bg-background px-3 text-sm"
              />
              <Button
                variant="outline"
                size="icon"
                aria-label="다음 날짜"
                onClick={() => setSelectedDate((date) => moveDate(date, 1))}
              >
                <ChevronRight />
              </Button>
              <Badge variant="outline"><Users data-icon="inline-start" />총 {games.length}경기</Badge>
            </div>
          </div>
          {isLoadingGames && <Card><CardContent className="py-10 text-center text-sm text-muted-foreground">경기 정보를 불러오는 중입니다.</CardContent></Card>}
          {!isLoadingGames && gamesError && <Card><CardContent className="py-10 text-center text-sm text-destructive">{gamesError}</CardContent></Card>}
          {!isLoadingGames && !gamesError && games.length === 0 && <Card><CardContent className="py-10 text-center text-sm text-muted-foreground">선택한 날짜에 예정된 경기가 없습니다.</CardContent></Card>}
          {!isLoadingGames && !gamesError && games.length > 0 && (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3" aria-label="선택 날짜의 경기 목록">
            {games.map((game) => {
              const existingPrediction = userPredictions.find(
                  (prediction) => prediction.gameId === game.id,
              )

              return (
                  <div
                      key={game.id}
                  >
                    <GameCard
                        game={game}
                        existingPrediction={existingPrediction ?? null}
                        onPredictionCreated={() => {
                          void refreshUser()
                          void loadUserPredictions()
                          void loadGames(selectedDate)
                        }}
                    />
                  </div>
              )
            })}
          </div>
          )}
        </section>

        <section
          className="grid gap-5 lg:grid-cols-[0.9fr_1.4fr]"
          aria-label="KBO 순위 및 커뮤니티"
        >
          <Card id="standings" className="scroll-mt-24">
            <CardHeader>
              <CardTitle>KBO 순위</CardTitle>
              <CardDescription>정규시즌 팀 순위</CardDescription>
              <CardAction>
                <Badge variant="outline">KBO 공식</Badge>
              </CardAction>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-[32px_1fr_64px_48px] gap-2 border-b pb-2 text-xs font-medium text-muted-foreground">
                <span>순위</span>
                <span>팀</span>
                <span className="text-right">승률</span>
                <span className="text-right">게임차</span>
              </div>
              {isLoadingStandings && (
                <div className="flex min-h-44 items-center justify-center py-8 text-center text-xs text-muted-foreground">
                  공식 KBO 순위를 불러오는 중입니다.
                </div>
              )}
              {!isLoadingStandings && standingsError && (
                <div className="flex min-h-44 flex-col items-center justify-center gap-3 py-8 text-center">
                  <p className="text-xs text-destructive">{standingsError}</p>
                  <Button variant="outline" size="sm" onClick={reloadStandings}>
                    다시 시도
                  </Button>
                </div>
              )}
              {!isLoadingStandings && !standingsError && standings.length === 0 && (
                <div className="flex min-h-44 flex-col items-center justify-center gap-3 py-8 text-center">
                  <span className="flex size-10 items-center justify-center rounded-full bg-muted text-muted-foreground">
                    <Trophy className="size-5" aria-hidden="true" />
                  </span>
                  <div>
                    <p className="text-sm font-semibold">표시할 순위가 없습니다.</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      정상 수집된 공식 10팀 순위를 기다리고 있습니다.
                    </p>
                  </div>
                </div>
              )}
              {!isLoadingStandings && !standingsError && standings.length > 0 && (
                <>
                  <div className="divide-y" aria-label="KBO 상위 5팀">
                    {standings.slice(0, 5).map((standing) => (
                      <div
                        key={standing.teamId}
                        className="grid grid-cols-[32px_1fr_64px_48px] items-center gap-2 py-3 text-sm"
                      >
                        <span className="font-mono font-black">{standing.rank}</span>
                        <span className="truncate font-semibold">{standing.teamName}</span>
                        <span className="text-right font-mono font-bold">
                          {formatStandingWinRate(standing.winRate)}
                        </span>
                        <span className="text-right font-mono">
                          {formatGamesBehind(standing.gamesBehind)}
                        </span>
                      </div>
                    ))}
                  </div>
                  <Link
                    to="/standings"
                    className="mt-3 flex justify-end text-xs font-semibold text-primary hover:underline"
                  >
                    전체 순위 보기 →
                  </Link>
                </>
              )}
            </CardContent>
          </Card>

          <Card id="community" className="scroll-mt-24">
            <CardHeader>
              <CardTitle>지금 뜨는 이야기</CardTitle>
              <CardDescription>
                야구팬들이 이야기를 나눌 공간
              </CardDescription>
              <CardAction>
                <Badge variant="outline">준비 중</Badge>
              </CardAction>
            </CardHeader>
            <CardContent className="flex min-h-56 flex-col items-center justify-center gap-3 py-8 text-center">
              <span className="flex size-10 items-center justify-center rounded-full bg-muted text-muted-foreground">
                <MessageCircle className="size-5" aria-hidden="true" />
              </span>
              <div>
                <p className="text-sm font-semibold">커뮤니티 준비 중</p>
                <p className="mt-1 text-xs text-muted-foreground">
                  팬들과 이야기를 나눌 공간을 준비하고 있습니다.
                </p>
              </div>
            </CardContent>
          </Card>
        </section>
      </main>
      <footer className="border-t"><div className="mx-auto flex max-w-7xl flex-col justify-between gap-3 px-4 py-6 text-xs text-muted-foreground sm:flex-row lg:px-6"><p>PLAYBALL · KBO 팬을 위한 데이터 기반 승부예측</p><p>본 예측은 참고용이며 사행성 행위를 조장하지 않습니다.</p></div></footer>
    </div>
  )
}
