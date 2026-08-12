import { useEffect, useRef, useState } from 'react'
import {
  ChevronLeft,
  ChevronRight,
  Sparkles,
  Users,
} from 'lucide-react'
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
  UserPredictionApiResponse,
} from '@/lib/api-types'

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
  cancelReason: string | null
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
  existingSelectedOutcome,
  onPredictionCreated,
}: {
  game: DashboardGame
  existingSelectedOutcome: PredictionOutcome | null
  onPredictionCreated: () => void
}) {
  const [pick, setPick] = useState<PredictionOutcome | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [predictionMessage, setPredictionMessage] = useState('')

  useEffect(() => {
    if (existingSelectedOutcome) {
      setPick(existingSelectedOutcome)
      setPredictionMessage(
        `${getOutcomeLabel(existingSelectedOutcome, game)}에 이미 예측했습니다.`,
      )
      return
    }

    setPick(null)
    setPredictionMessage('')
  }, [
    existingSelectedOutcome,
    game.away,
    game.home,
  ])

  const submitPrediction = async (selectedOutcome: PredictionOutcome) => {
    try {
      setIsSubmitting(true)
      setPredictionMessage('')

      const response = await fetch('/api/user-predictions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          gameId: game.id,
          selectedOutcome,
          pointAmount: 100,
        }),
        credentials: 'include',
      })

      const responseBody = await response.json().catch(() => null)

      if (!response.ok) {
        throw new Error(
            responseBody?.detail ??
            responseBody?.message ??
            '승부예측 등록에 실패했습니다.',
        )
      }

      setPick(selectedOutcome)
      setPredictionMessage(
        `${getOutcomeLabel(selectedOutcome, game)}에 100포인트를 예측했습니다.`,
      )
      onPredictionCreated()
    } catch (error) {
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
              {showScore ? scoreLabel(game.awayScore) : '-'}
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
              {showScore ? scoreLabel(game.homeScore) : '-'}
            </span>
          </div>
        </div>

        <div className="rounded-lg bg-muted/60 p-3">
          <p className="mb-2 text-center text-xs font-semibold text-muted-foreground">
            AI 예측 확률
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
                  !game.userOdds?.bettingOpen ||
                  !data ||
                  isSubmitting ||
                  pick !== null
                }
                variant={pick === outcome ? 'default' : 'outline'}
                onClick={() => submitPrediction(outcome)}
              >
                <span className="max-w-full truncate font-semibold">
                  {getOutcomeLabel(outcome, game)}
                </span>
                <span className="text-[11px] opacity-70">
                  사용자 {formatBettingRate(data?.userBettingRate ?? null)}
                </span>
                <span className="font-mono text-xs font-bold">
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

        {game.userOdds && !game.userOdds.bettingOpen && (
          <p className="text-center text-xs text-muted-foreground">
            {game.userOdds.finalized ? '예측 마감 · 최종 배당' : '예측 참여 불가'}
          </p>
        )}

        {predictionMessage && (
          <p className="text-center text-xs font-medium text-primary">
            {predictionMessage}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

export function KboDashboard() {
  const { user, isLoading: isAuthLoading, refreshUser } = useAuth()
  const [selectedDate, setSelectedDate] = useState(getKoreaDate)
  const [games, setGames] = useState<DashboardGame[]>([])
  const [isLoadingGames, setIsLoadingGames] = useState(true)
  const [gamesError, setGamesError] = useState('')
  const gamesRequestIdRef = useRef(0)

  const [userPredictions, setUserPredictions] = useState<
      UserPredictionApiResponse[]
  >([])

  const loadUserPredictions = async () => {
    try {
      const response = await fetch(
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

  const loadGames = async (date: string, signal?: AbortSignal) => {
    const requestId = ++gamesRequestIdRef.current

    try {
      setIsLoadingGames(true)
      setGamesError('')
      setGames([])

      const gameResponse = await fetch(
        `/api/games?date=${encodeURIComponent(date)}`,
        { signal },
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
            cancelReason: game.cancelReason,
          }
        },
      )

      if (requestId === gamesRequestIdRef.current) {
        setGames(dashboardGames)
      }
    } catch (error) {
      if (signal?.aborted) return
      if (requestId === gamesRequestIdRef.current) {
        console.error(error)
        setGames([])
        setGamesError('경기 정보를 불러오지 못했습니다.')
      }
    } finally {
      if (requestId === gamesRequestIdRef.current && !signal?.aborted) {
        setIsLoadingGames(false)
      }
    }
  }

  useEffect(() => {
    if (isAuthLoading) return

    if (user) {
      void loadUserPredictions()
    } else {
      setUserPredictions([])
    }
  }, [isAuthLoading, user?.id])

  useEffect(() => {
    const controller = new AbortController()
    void loadGames(selectedDate, controller.signal)

    return () => controller.abort()
  }, [selectedDate])

  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-7xl flex-col gap-8 px-4 py-7 lg:px-6 lg:py-10">
        <section className="flex flex-col gap-5 rounded-2xl bg-primary p-6 text-primary-foreground md:p-8">
          <div className="flex max-w-2xl flex-col gap-3">
            <Badge variant="secondary" className="w-fit"><Sparkles data-icon="inline-start" />데이터 기반 KBO 승부예측</Badge>
            <h1 className="text-balance text-3xl font-black tracking-tight md:text-4xl">오늘의 승부, 당신의 선택은?</h1>
            <p className="max-w-xl text-pretty text-sm leading-relaxed text-primary-foreground/70 md:text-base">선발, 타선, 불펜 데이터를 분석한 예측을 확인하고 야구팬들과 함께 오늘의 승자를 맞혀보세요.</p>
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
                        existingSelectedOutcome={
                            existingPrediction?.selectedOutcome ?? null
                        }
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
      </main>
      <footer className="border-t"><div className="mx-auto flex max-w-7xl flex-col justify-between gap-3 px-4 py-6 text-xs text-muted-foreground sm:flex-row lg:px-6"><p>PLAYBALL · KBO 팬을 위한 데이터 기반 승부예측</p><p>본 예측은 참고용이며 사행성 행위를 조장하지 않습니다.</p></div></footer>
    </div>
  )
}
