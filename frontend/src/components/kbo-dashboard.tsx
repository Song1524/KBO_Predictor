import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ChevronLeft,
  ChevronRight,
  MessageCircle,
  Sparkles,
  Trophy,
  Users,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/auth-context'
import { AppHeader } from '@/components/app-header'
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
import type {
  PredictionOutcome,
  UserApiResponse,
  UserPredictionApiResponse,
} from '@/lib/api-types'
import { apiFetch } from '@/lib/api-client'
import { useStandings } from '@/lib/use-standings'

const MIN_PREDICTION_POINTS = 100
const PREDICTION_POINT_UNIT = 100
const QUICK_POINT_AMOUNTS = [100, 300, 500] as const
const GAMES_POLLING_INTERVAL_MS = 30_000

type GameStatus =
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'FINISHED'
  | 'CANCELLED'

type OutcomeOddsApiResponse = {
  outcome: PredictionOutcome
  betPoints: number | null
  userBettingRate: number | null
  odds: number | null
}

type GameOddsApiResponse = {
  gameId: number
  totalBetPoints: number | null
  homeWin: OutcomeOddsApiResponse | null
  draw: OutcomeOddsApiResponse | null
  awayWin: OutcomeOddsApiResponse | null
  bettingOpen: boolean | null
  finalized: boolean | null
  predictionCloseAt: string | null
  finalizedAt: string | null
}

type SystemPredictionApiResponse = {
  gameId: number
  homeWinProbability: number | null
  drawProbability: number | null
  awayWinProbability: number | null
}

type GameApiResponse = {
  id: number
  season: number
  gameDate: string
  gameTime: string | null
  homeTeamId: number | null
  homeTeamName: string | null
  awayTeamId: number | null
  awayTeamName: string | null
  homeStartingPitcherPlayerId: number | null
  homeStartingPitcherName: string | null
  awayStartingPitcherPlayerId: number | null
  awayStartingPitcherName: string | null
  stadium: string | null
  status: GameStatus | null
  homeScore: number | null
  awayScore: number | null
  winnerTeamId: number | null
  result: PredictionOutcome | null
  predictionCloseAt: string | null
  cancelReason: string | null
  aiPrediction: SystemPredictionApiResponse | null
  userOdds: GameOddsApiResponse | null
}

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
  awayPct: number | null
  drawPct: number | null
  homePct: number | null
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

function getOutcomeLabel(
  outcome: PredictionOutcome,
  game: DashboardGame,
) {
  switch (outcome) {
    case 'HOME_WIN':
      return `${game.home} 승`
    case 'DRAW':
      return '무승부'
    case 'AWAY_WIN':
      return `${game.away} 승`
  }
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

function formatOdds(value: number | null) {
  return value == null || !Number.isFinite(Number(value))
    ? '-'
    : `${Number(value).toFixed(2)}배`
}

function formatBettingRate(value: number | null) {
  return value == null || !Number.isFinite(Number(value))
    ? '-'
    : `${Math.round(Number(value))}%`
}

function formatTotalBetPoints(value: number | null) {
  return value == null || !Number.isFinite(Number(value))
    ? '투표 정보 없음'
    : `${Number(value).toLocaleString()}P 참여`
}

function formatPredictionCloseAt(value: string | null) {
  if (!value) return '마감 시각 미정'
  return value.replace('T', ' ').slice(0, 16)
}

function parsePredictionCloseAt(value: string | null) {
  if (!value) return null
  const hasOffset = /(Z|[+-]\d{2}:\d{2})$/i.test(value)
  const timestamp = Date.parse(hasOffset ? value : `${value}+09:00`)
  return Number.isFinite(timestamp) ? timestamp : null
}

function usePredictionDeadlineReached(value: string | null) {
  const [deadlineReached, setDeadlineReached] = useState(() => {
    const deadline = parsePredictionCloseAt(value)
    return deadline != null && Date.now() >= deadline
  })

  useEffect(() => {
    const deadline = parsePredictionCloseAt(value)
    if (deadline == null) {
      setDeadlineReached(false)
      return
    }

    let timer: number | undefined
    const updateDeadline = () => {
      const remaining = deadline - Date.now()
      setDeadlineReached(remaining <= 0)
      if (remaining > 0) {
        timer = window.setTimeout(
          updateDeadline,
          Math.min(remaining, 2_147_483_647),
        )
      }
    }

    updateDeadline()
    return () => {
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [value])

  return deadlineReached
}

function getOutcomeOdds(
  game: DashboardGame,
  outcome: PredictionOutcome,
) {
  switch (outcome) {
    case 'HOME_WIN':
      return game.userOdds?.homeWin ?? null
    case 'DRAW':
      return game.userOdds?.draw ?? null
    case 'AWAY_WIN':
      return game.userOdds?.awayWin ?? null
  }
}

function scoreLabel(score: number | null) {
  return score == null ? '정보 없음' : `${score}점`
}

function TeamMark({ teamName }: { teamName: string }) {
  return (
    <div className="flex size-12 items-center justify-center rounded-full bg-primary font-mono text-sm font-black text-primary-foreground">
      {getTeamMark(teamName)}
    </div>
  )
}

function GameCard({
  game,
  existingPrediction,
  user,
  isAuthLoading,
  onPredictionCreated,
}: {
  game: DashboardGame
  existingPrediction: UserPredictionApiResponse | null
  user: UserApiResponse | null
  isAuthLoading: boolean
  onPredictionCreated: () => void
}) {
  const [pick, setPick] = useState<PredictionOutcome | null>(null)
  const [pointAmount, setPointAmount] = useState(MIN_PREDICTION_POINTS)
  const [submittedPrediction, setSubmittedPrediction] =
    useState<UserPredictionApiResponse | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [predictionMessage, setPredictionMessage] = useState('')
  const [hasPredictionError, setHasPredictionError] = useState(false)
  const confirmedPrediction = existingPrediction ?? submittedPrediction
  const predictionCloseAt =
    game.userOdds?.predictionCloseAt ?? game.predictionCloseAt
  const deadlineReached = usePredictionDeadlineReached(predictionCloseAt)
  const bettingOpen =
    game.userOdds?.bettingOpen === true && !deadlineReached

  useEffect(() => {
    if (existingPrediction) {
      setSubmittedPrediction(null)
      setPick(existingPrediction.selectedOutcome)
      setPointAmount(existingPrediction.pointAmount)
      setHasPredictionError(false)
      setPredictionMessage(
        `${getOutcomeLabel(existingPrediction.selectedOutcome, game)}에 이미 예측했습니다.`,
      )
      return
    }

    setSubmittedPrediction(null)
    setPick(null)
    setPointAmount(MIN_PREDICTION_POINTS)
    setPredictionMessage('')
    setHasPredictionError(false)
  }, [
    existingPrediction?.id,
    existingPrediction?.pointAmount,
    existingPrediction?.selectedOutcome,
    game.away,
    game.home,
    user?.id,
  ])

  const requestLogin = () => {
    setHasPredictionError(true)
    setPredictionMessage('로그인 후 예측할 수 있습니다. 로그인해 주세요.')
    window.dispatchEvent(new Event('playball:open-login'))
  }

  const selectOutcome = (outcome: PredictionOutcome) => {
    if (!user) {
      requestLogin()
      return
    }
    if (confirmedPrediction) return

    setPick(outcome)
    setPredictionMessage('')
    setHasPredictionError(false)
  }

  const pointAmountIsValid =
    Number.isInteger(pointAmount) &&
    pointAmount >= MIN_PREDICTION_POINTS &&
    pointAmount % PREDICTION_POINT_UNIT === 0
  const hasEnoughPoints = user != null && user.point >= pointAmount
  const selectedOdds = pick ? getOutcomeOdds(game, pick) : null
  const canSubmit =
    pick != null &&
    user != null &&
    bettingOpen &&
    !confirmedPrediction &&
    !isSubmitting &&
    pointAmountIsValid &&
    hasEnoughPoints

  const submitPrediction = async () => {
    if (!user) {
      requestLogin()
      return
    }
    if (!pick || !canSubmit) return

    try {
      setIsSubmitting(true)
      setPredictionMessage('')
      setHasPredictionError(false)

      const response = await apiFetch('/api/user-predictions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          gameId: game.id,
          selectedOutcome: pick,
          pointAmount,
        }),
        credentials: 'include',
      })

      const responseBody = await response.json().catch(() => null)

      if (!response.ok) {
        if (response.status === 401) requestLogin()
        throw new Error(
            responseBody?.detail ??
            responseBody?.message ??
            '승부예측 등록에 실패했습니다.',
        )
      }

      const createdPrediction = responseBody as UserPredictionApiResponse
      setSubmittedPrediction(createdPrediction)
      setPick(createdPrediction.selectedOutcome)
      setPointAmount(createdPrediction.pointAmount)
      setPredictionMessage(
        `${getOutcomeLabel(createdPrediction.selectedOutcome, game)}에 ${createdPrediction.pointAmount.toLocaleString()}P를 예측했습니다.`,
      )
      onPredictionCreated()
    } catch (error) {
      setHasPredictionError(true)
      setPredictionMessage(
          error instanceof Error
              ? error.message
              : '승부예측 등록 중 오류가 발생했습니다.',
      )
    } finally {
      setIsSubmitting(false)
    }
  }

  const outcomeOptions: Array<{
    outcome: PredictionOutcome
    data: OutcomeOddsApiResponse | null
  }> | null = game.userOdds
    ? [
        { outcome: 'AWAY_WIN', data: game.userOdds.awayWin },
        { outcome: 'DRAW', data: game.userOdds.draw },
        { outcome: 'HOME_WIN', data: game.userOdds.homeWin },
      ]
    : null
  const showScore =
    game.status === 'IN_PROGRESS' || game.status === 'FINISHED'

  return (
    <Card className="transition-shadow hover:shadow-md">
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
            ? ` · ${game.cancelReason}`
            : ''}
        </CardDescription>

        <CardAction>
          <span className="text-xs text-muted-foreground">
            {game.userOdds
              ? formatTotalBetPoints(game.userOdds.totalBetPoints)
              : '투표 정보 없음'}
          </span>
        </CardAction>
      </CardHeader>

      <CardContent className="flex flex-col gap-5">
        <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-3">
          <div className="flex flex-col items-center gap-2 text-center">
            <TeamMark
              teamName={game.away}
            />
            <strong className="text-base">{game.away}</strong>
            <span className="text-xs text-muted-foreground">
              {showScore
                ? scoreLabel(game.awayScore)
                : game.awayStartingPitcherName ?? '선발 미정'}
            </span>
          </div>

          <div className="font-mono text-xs font-semibold text-muted-foreground">
            VS
          </div>

          <div className="flex flex-col items-center gap-2 text-center">
            <TeamMark
              teamName={game.home}
            />
            <strong className="text-base">{game.home}</strong>
            <span className="text-xs text-muted-foreground">
              {showScore
                ? scoreLabel(game.homeScore)
                : game.homeStartingPitcherName ?? '선발 미정'}
            </span>
          </div>
        </div>

        <div className="rounded-lg bg-muted/60 p-3">
          <p className="mb-2 text-center text-xs font-semibold text-muted-foreground">
            데이터 예측 확률
          </p>
          <div className="grid grid-cols-3 gap-2 text-center text-xs">
            <div><strong>{formatProbability(game.awayPct)}</strong><p>{game.away} 승</p></div>
            <div><strong>{formatProbability(game.drawPct)}</strong><p>무승부</p></div>
            <div><strong>{formatProbability(game.homePct)}</strong><p>{game.home} 승</p></div>
          </div>
        </div>

        {outcomeOptions ? (
          <div className="grid grid-cols-3 gap-2">
            {outcomeOptions.map(({ outcome, data }) => (
              <Button
                key={outcome}
                className="h-auto min-h-24 flex-col gap-1 px-2 py-3"
                disabled={
                  !bettingOpen ||
                  !data ||
                  isSubmitting ||
                  confirmedPrediction !== null ||
                  isAuthLoading
                }
                variant={pick === outcome ? 'default' : 'outline'}
                onClick={() => selectOutcome(outcome)}
              >
                <span className="max-w-full truncate font-semibold">
                  {getOutcomeLabel(outcome, game)}
                </span>
                <span className="text-[11px] opacity-70">
                  포인트 비율 {formatBettingRate(data?.userBettingRate ?? null)}
                </span>
                <span className="font-mono text-xs font-bold">
                  {game.userOdds?.finalized ? '최종 ' : '현재 '}
                  {formatOdds(data?.odds ?? null)}
                </span>
              </Button>
            ))}
          </div>
        ) : (
          <p className="rounded-lg border py-5 text-center text-xs text-muted-foreground">
            투표 정보가 없습니다.
          </p>
        )}

        <p className="text-center text-xs text-muted-foreground">
          예측 마감 {formatPredictionCloseAt(predictionCloseAt)}
        </p>

        {pick && !confirmedPrediction && user && (
          <div className="grid gap-4 rounded-xl border bg-muted/30 p-4">
            <div>
              <p className="text-sm font-bold">예측 정보 확인</p>
              <p className="mt-1 text-xs text-muted-foreground">
                결과와 포인트를 확인한 뒤 최종 제출해 주세요.
              </p>
            </div>

            <div className="flex flex-wrap gap-2">
              {QUICK_POINT_AMOUNTS.map((amount) => (
                <Button
                  key={amount}
                  type="button"
                  size="xs"
                  variant={pointAmount === amount ? 'default' : 'outline'}
                  disabled={isSubmitting || amount > user.point}
                  onClick={() => setPointAmount(amount)}
                >
                  {amount}P
                </Button>
              ))}
            </div>

            <div className="grid grid-cols-[auto_1fr_auto] gap-2">
              <Button
                type="button"
                variant="outline"
                size="icon-sm"
                aria-label="참여 포인트 100P 줄이기"
                disabled={
                  isSubmitting || pointAmount <= MIN_PREDICTION_POINTS
                }
                onClick={() => setPointAmount((amount) =>
                  Math.max(MIN_PREDICTION_POINTS, amount - PREDICTION_POINT_UNIT),
                )}
              >
                −
              </Button>
              <label className="grid gap-1 text-xs font-medium">
                참여 포인트
                <input
                  className="h-9 rounded-md border bg-background px-3 text-right font-mono text-sm"
                  type="number"
                  min={MIN_PREDICTION_POINTS}
                  max={user.point}
                  step={PREDICTION_POINT_UNIT}
                  inputMode="numeric"
                  value={pointAmount}
                  disabled={isSubmitting}
                  onChange={(event) => setPointAmount(Number(event.target.value))}
                />
              </label>
              <Button
                type="button"
                variant="outline"
                size="icon-sm"
                aria-label="참여 포인트 100P 늘리기"
                disabled={
                  isSubmitting || pointAmount + PREDICTION_POINT_UNIT > user.point
                }
                onClick={() => setPointAmount((amount) =>
                  amount + PREDICTION_POINT_UNIT,
                )}
              >
                +
              </Button>
            </div>

            <dl className="grid gap-2 text-xs sm:grid-cols-2">
              <div><dt className="text-muted-foreground">선택</dt><dd className="mt-0.5 font-semibold">{getOutcomeLabel(pick, game)}</dd></div>
              <div><dt className="text-muted-foreground">참여 포인트</dt><dd className="mt-0.5 font-mono font-semibold">{Number.isFinite(pointAmount) ? pointAmount.toLocaleString() : '-'}P</dd></div>
              <div><dt className="text-muted-foreground">현재 보유 포인트</dt><dd className="mt-0.5 font-mono font-semibold">{user.point.toLocaleString()}P</dd></div>
              <div><dt className="text-muted-foreground">참여 후 예상 잔액</dt><dd className="mt-0.5 font-mono font-semibold">{pointAmountIsValid && hasEnoughPoints ? (user.point - pointAmount).toLocaleString() : '-'}P</dd></div>
              <div><dt className="text-muted-foreground">현재 배당</dt><dd className="mt-0.5 font-mono font-semibold">{formatOdds(selectedOdds?.odds ?? null)}</dd></div>
            </dl>

            {!pointAmountIsValid && (
              <p className="text-xs font-medium text-destructive">
                참여 포인트는 100P 이상, 100P 단위로 입력해 주세요.
              </p>
            )}
            {pointAmountIsValid && !hasEnoughPoints && (
              <p className="text-xs font-medium text-destructive">
                포인트가 부족합니다.
              </p>
            )}
            <p className="text-[11px] leading-relaxed text-muted-foreground">
              현재 배당은 다른 사용자의 참여에 따라 마감 전까지 변동되며,
              적중 시 지급 포인트는 마감 시 확정된 최종 배당으로 계산됩니다.
            </p>
            <Button disabled={!canSubmit} onClick={() => void submitPrediction()}>
              {isSubmitting
                ? '예측 등록 중...'
                : `${Number.isFinite(pointAmount) ? pointAmount.toLocaleString() : '-'}P로 예측하기`}
            </Button>
          </div>
        )}

        {confirmedPrediction && (
          <div className="grid gap-2 rounded-xl border border-primary/30 bg-primary/5 p-4 text-sm">
            <div className="flex items-center justify-between gap-2">
              <strong>이미 예측했습니다</strong>
              <Badge variant="outline">변경 불가</Badge>
            </div>
            <p>
              선택 <strong>{getOutcomeLabel(confirmedPrediction.selectedOutcome, game)}</strong>
              <span className="mx-2 text-muted-foreground">·</span>
              참여 <strong className="font-mono">{confirmedPrediction.pointAmount.toLocaleString()}P</strong>
            </p>
            {!game.userOdds?.bettingOpen && game.userOdds?.finalized && (
              <p className="text-xs text-muted-foreground">
                선택 결과 최종 배당 {formatOdds(getOutcomeOdds(game, confirmedPrediction.selectedOutcome)?.odds ?? null)}
              </p>
            )}
          </div>
        )}

        {game.userOdds && !bettingOpen && (
          <p className="text-center text-xs text-muted-foreground">
            {game.userOdds.finalized
              ? '예측 마감 · 최종 배당'
              : deadlineReached
                ? '예측 마감 · 최종 배당 확정 중'
                : '예측 참여 불가'}
          </p>
        )}

        {predictionMessage && (
          <p className={`text-center text-xs font-medium ${hasPredictionError ? 'text-destructive' : 'text-primary'}`}>
            {predictionMessage}
          </p>
        )}
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
          const aiHome = toFiniteNumber(
            game.aiPrediction?.homeWinProbability,
          )
          const aiDraw = toFiniteNumber(
            game.aiPrediction?.drawProbability,
          )
          const aiAway = toFiniteNumber(
            game.aiPrediction?.awayWinProbability,
          )

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
            awayPct: aiAway,
            drawPct: aiDraw,
            homePct: aiHome,
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
                        user={user}
                        isAuthLoading={isAuthLoading}
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
