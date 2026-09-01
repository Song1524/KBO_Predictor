import { useEffect, useState } from 'react'
import {
  ArrowLeft,
  CalendarDays,
  Clock3,
  MapPin,
  Sparkles,
  UserRound,
} from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { useAuth } from '@/auth-context'
import { AppHeader } from '@/components/app-header'
import {
  GamePredictionPanel,
  getOutcomeLabel,
  type PredictionGame,
} from '@/components/game-prediction-panel'
import { Badge } from '@/components/ui/badge'
import { buttonVariants } from '@/components/ui/button'
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { apiFetch } from '@/lib/api-client'
import type { UserPredictionApiResponse } from '@/lib/api-types'
import type {
  GameApiResponse,
  GameStatus,
  SystemPredictionApiResponse,
  TeamStatApiResponse,
} from '@/lib/game-api-types'
import { cn } from '@/lib/utils'

function normalizeText(value: string | null, fallback: string) {
  const normalized = value?.trim()
  return normalized || fallback
}

function getTeamMark(teamName: string) {
  return teamName === '정보 없음'
    ? '-'
    : teamName.split(/\s+/)[0].slice(0, 3).toUpperCase()
}

function getGameStatusLabel(status: GameStatus | null) {
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
      return '상태 미정'
  }
}

function formatGameDate(date: string) {
  const parsed = new Date(`${date}T00:00:00+09:00`)
  if (Number.isNaN(parsed.getTime())) return date

  return parsed.toLocaleDateString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
  })
}

function formatGameTime(value: string | null) {
  return value?.slice(0, 5) || '시간 미정'
}

function formatDateTime(value: string | null) {
  if (!value) return null
  return value.replace('T', ' ').slice(0, 16)
}

function toFiniteNumber(value: number | null | undefined) {
  if (value == null) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function formatProbability(value: number | null) {
  return value == null ? '-' : `${value.toFixed(1)}%`
}

function probabilityBarWidth(value: number) {
  return `${Math.min(100, Math.max(0, value))}%`
}

function formatRecord(
  wins: number | null,
  losses: number | null,
  draws: number | null,
) {
  if (wins == null && losses == null && draws == null) return '-'
  return `${wins ?? '-'}승 ${losses ?? '-'}패 ${draws ?? '-'}무`
}

function formatBaseballRate(value: number | null) {
  if (value == null || !Number.isFinite(Number(value))) return '-'
  return Number(value).toFixed(3).replace(/^0/, '')
}

function formatDecimal(value: number | null, digits = 2) {
  if (value == null || !Number.isFinite(Number(value))) return '-'
  return Number(value).toFixed(digits)
}

function formatRuns(
  scored: number | null,
  allowed: number | null,
) {
  if (scored == null && allowed == null) return '-'
  return `${formatDecimal(scored)} / ${formatDecimal(allowed)}`
}

function formatTotalBetPoints(value: number | null | undefined) {
  return value == null || !Number.isFinite(Number(value))
    ? '참여 포인트 정보 없음'
    : `총 ${Number(value).toLocaleString()}P 참여`
}

function TeamMark({ teamName }: { teamName: string }) {
  return (
    <div className="flex size-14 items-center justify-center rounded-full bg-primary font-mono text-sm font-black text-primary-foreground sm:size-16 sm:text-base">
      {getTeamMark(teamName)}
    </div>
  )
}

function SectionHeading({
  eyebrow,
  title,
  description,
}: {
  eyebrow: string
  title: string
  description: string
}) {
  return (
    <div>
      <p className="text-xs font-bold uppercase tracking-wide text-primary">
        {eyebrow}
      </p>
      <h2 className="mt-1 text-xl font-black tracking-tight">{title}</h2>
      <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
        {description}
      </p>
    </div>
  )
}

function AiAnalysis({
  game,
  homeName,
  awayName,
}: {
  game: GameApiResponse
  homeName: string
  awayName: string
}) {
  const prediction = game.aiPrediction
  if (!prediction) {
    return (
      <section className="rounded-2xl border bg-card p-5 sm:p-6 lg:col-span-2">
        <SectionHeading
          eyebrow="AI 분석"
          title="AI 분석 준비 중"
          description="이 경기에 저장된 시스템 예측이 아직 없습니다."
        />
      </section>
    )
  }

  const probabilities = getPredictionProbabilities(prediction)
  const predictionGame: Pick<PredictionGame, 'home' | 'away'> = {
    home: homeName,
    away: awayName,
  }
  const predictedLabel = prediction.predictedOutcome
    ? getOutcomeLabel(prediction.predictedOutcome, predictionGame)
    : '예상 결과 확인 중'
  const reason = prediction.reason?.trim()

  return (
    <section className="rounded-2xl border bg-card p-5 sm:p-6 lg:col-span-2">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
        <SectionHeading
          eyebrow="AI 분석"
          title={`AI 예상 · ${predictedLabel}`}
          description="백엔드에 저장된 시스템 예측 확률입니다."
        />
        {prediction.modelVersion && (
          <Badge variant="outline">{prediction.modelVersion}</Badge>
        )}
      </div>

      {probabilities ? (
        <div className="mt-6">
          <div
            className="flex h-3 overflow-hidden rounded-full bg-border"
            role="img"
            aria-label={probabilities
              .map(({ label, value }) => `${label} ${formatProbability(value)}`)
              .join(', ')}
          >
            {probabilities.map(({ outcome, value, color }) => (
              <span
                key={outcome}
                className={`${color} ${prediction.predictedOutcome === outcome ? 'opacity-100' : 'opacity-50'}`}
                style={{ width: probabilityBarWidth(value) }}
              />
            ))}
          </div>
          <div className="mt-3 grid grid-cols-3 gap-2 text-center">
            {probabilities.map(({ outcome, label, value }) => (
              <div
                key={outcome}
                className={prediction.predictedOutcome === outcome
                  ? 'text-primary'
                  : 'text-muted-foreground'}
              >
                <p className="text-xs font-semibold">{label}</p>
                <p className="mt-0.5 font-mono text-lg font-black">
                  {formatProbability(value)}
                </p>
              </div>
            ))}
          </div>
        </div>
      ) : (
        <p className="mt-5 rounded-xl bg-muted/50 p-4 text-sm text-muted-foreground">
          AI 확률 데이터가 아직 준비되지 않았습니다.
        </p>
      )}

      <div className="mt-6 border-t pt-5">
        <h3 className="flex items-center gap-2 text-sm font-bold">
          <Sparkles className="size-4 text-primary" />
          상세 예측 근거
        </h3>
        {reason ? (
          <p className="mt-3 whitespace-pre-line text-sm leading-7 text-foreground/80">
            {reason}
          </p>
        ) : (
          <p className="mt-3 text-sm text-muted-foreground">
            제공된 상세 예측 근거가 없습니다.
          </p>
        )}
        {formatDateTime(prediction.generatedAt) && (
          <p className="mt-4 text-xs text-muted-foreground">
            분석 생성 {formatDateTime(prediction.generatedAt)}
          </p>
        )}
      </div>
    </section>
  )
}

function getPredictionProbabilities(prediction: SystemPredictionApiResponse) {
  const home = toFiniteNumber(prediction.homeWinProbability)
  const draw = toFiniteNumber(prediction.drawProbability)
  const away = toFiniteNumber(prediction.awayWinProbability)
  if (home == null || draw == null || away == null) return null

  return [
    { outcome: 'HOME_WIN' as const, label: '홈 승', value: home, color: 'bg-primary' },
    { outcome: 'DRAW' as const, label: '무승부', value: draw, color: 'bg-muted-foreground/50' },
    { outcome: 'AWAY_WIN' as const, label: '원정 승', value: away, color: 'bg-accent' },
  ]
}

function StartingPitcherComparison({
  game,
  homeName,
  awayName,
}: {
  game: GameApiResponse
  homeName: string
  awayName: string
}) {
  return (
    <section className="rounded-2xl border bg-card p-5 sm:p-6">
      <SectionHeading
        eyebrow="선발투수"
        title="선발투수 비교"
        description="현재 경기 응답에 확정된 선발 정보입니다."
      />
      <div className="mt-6 grid grid-cols-2 gap-3">
        <PitcherSide
          side="원정"
          teamName={awayName}
          pitcherName={game.awayStartingPitcherName}
        />
        <PitcherSide
          side="홈"
          teamName={homeName}
          pitcherName={game.homeStartingPitcherName}
        />
      </div>
      <p className="mt-5 border-t pt-4 text-xs leading-relaxed text-muted-foreground">
        선발투수 ERA와 승패 기록은 현재 공개 API 응답에 포함되지 않습니다.
      </p>
    </section>
  )
}

function PitcherSide({
  side,
  teamName,
  pitcherName,
}: {
  side: string
  teamName: string
  pitcherName: string | null
}) {
  return (
    <div className="min-w-0 rounded-xl bg-muted/45 p-4 text-center">
      <UserRound className="mx-auto size-7 text-primary" />
      <p className="mt-3 text-xs font-semibold text-muted-foreground">
        {side} · {teamName}
      </p>
      <p className="mt-1 truncate text-base font-black" title={pitcherName ?? '선발 미정'}>
        {normalizeText(pitcherName, '선발 미정')}
      </p>
    </div>
  )
}

function TeamStatComparison({
  homeName,
  awayName,
  homeStat,
  awayStat,
  isLoading,
  error,
}: {
  homeName: string
  awayName: string
  homeStat: TeamStatApiResponse | null
  awayStat: TeamStatApiResponse | null
  isLoading: boolean
  error: string
}) {
  return (
    <section className="rounded-2xl border bg-card p-5 sm:p-6">
      <SectionHeading
        eyebrow="팀 데이터"
        title="시즌 성적 비교"
        description="각 팀의 가장 최근 수집 기록을 비교합니다."
      />

      {isLoading ? (
        <p className="mt-8 py-10 text-center text-sm text-muted-foreground">
          팀 성적을 불러오는 중입니다.
        </p>
      ) : !homeStat && !awayStat ? (
        <p className="mt-8 rounded-xl bg-muted/45 px-4 py-10 text-center text-sm text-muted-foreground">
          양 팀의 최신 통계가 없습니다.
        </p>
      ) : (
        <div className="mt-6">
          <div className="grid grid-cols-[minmax(0,1fr)_76px_minmax(0,1fr)] items-end gap-2 border-b pb-3 text-center">
            <div className="min-w-0">
              <strong className="block truncate text-sm" title={awayName}>{awayName}</strong>
              {!awayStat && <span className="text-[10px] text-muted-foreground">통계 없음</span>}
            </div>
            <span className="text-[11px] font-semibold text-muted-foreground">항목</span>
            <div className="min-w-0">
              <strong className="block truncate text-sm" title={homeName}>{homeName}</strong>
              {!homeStat && <span className="text-[10px] text-muted-foreground">통계 없음</span>}
            </div>
          </div>
          <ComparisonRow label="시즌" away={formatRecord(awayStat?.wins ?? null, awayStat?.losses ?? null, awayStat?.draws ?? null)} home={formatRecord(homeStat?.wins ?? null, homeStat?.losses ?? null, homeStat?.draws ?? null)} />
          <ComparisonRow label="승률" away={formatBaseballRate(awayStat?.winRate ?? null)} home={formatBaseballRate(homeStat?.winRate ?? null)} />
          <ComparisonRow label="최근 10" away={formatRecord(awayStat?.recent10Wins ?? null, awayStat?.recent10Losses ?? null, awayStat?.recent10Draws ?? null)} home={formatRecord(homeStat?.recent10Wins ?? null, homeStat?.recent10Losses ?? null, homeStat?.recent10Draws ?? null)} />
          <ComparisonRow label="최근 승률" away={formatBaseballRate(awayStat?.recent10WinRate ?? null)} home={formatBaseballRate(homeStat?.recent10WinRate ?? null)} />
          <ComparisonRow label="홈 성적" away={formatRecord(awayStat?.homeWins ?? null, awayStat?.homeLosses ?? null, awayStat?.homeDraws ?? null)} home={formatRecord(homeStat?.homeWins ?? null, homeStat?.homeLosses ?? null, homeStat?.homeDraws ?? null)} />
          <ComparisonRow label="원정 성적" away={formatRecord(awayStat?.awayWins ?? null, awayStat?.awayLosses ?? null, awayStat?.awayDraws ?? null)} home={formatRecord(homeStat?.awayWins ?? null, homeStat?.awayLosses ?? null, homeStat?.awayDraws ?? null)} />
          <ComparisonRow label="팀 타율" away={formatBaseballRate(awayStat?.battingAverage ?? null)} home={formatBaseballRate(homeStat?.battingAverage ?? null)} />
          <ComparisonRow label="팀 ERA" away={formatDecimal(awayStat?.era ?? null)} home={formatDecimal(homeStat?.era ?? null)} />
          <ComparisonRow label="10G 득/실" away={formatRuns(awayStat?.recent10AvgRuns ?? null, awayStat?.recent10AvgRunsAllowed ?? null)} home={formatRuns(homeStat?.recent10AvgRuns ?? null, homeStat?.recent10AvgRunsAllowed ?? null)} />
        </div>
      )}

      {error && (
        <p className="mt-4 text-xs text-destructive">{error}</p>
      )}
      {!isLoading && (homeStat || awayStat) && (
        <p className="mt-4 text-xs text-muted-foreground">
          기준일 {awayStat?.statDate ?? homeStat?.statDate ?? '-'}
        </p>
      )}
    </section>
  )
}

function ComparisonRow({
  label,
  away,
  home,
}: {
  label: string
  away: string
  home: string
}) {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)_76px_minmax(0,1fr)] items-center gap-2 border-b py-3 text-center last:border-b-0">
      <span className="min-w-0 break-keep font-mono text-xs font-semibold sm:text-sm">{away}</span>
      <span className="text-[11px] font-semibold text-muted-foreground">{label}</span>
      <span className="min-w-0 break-keep font-mono text-xs font-semibold sm:text-sm">{home}</span>
    </div>
  )
}

async function fetchTeamStat(
  teamId: number | null,
  signal: AbortSignal,
): Promise<TeamStatApiResponse | null> {
  if (teamId == null) return null

  const response = await apiFetch(`/api/teams/${teamId}/stats/latest`, {
    signal,
  })
  if (response.status === 404) return null
  if (!response.ok) throw new Error('팀 성적을 불러오지 못했습니다.')
  return response.json() as Promise<TeamStatApiResponse>
}

export function GameDetailPage() {
  const { gameId: gameIdParam } = useParams()
  const { user, isLoading: isAuthLoading, refreshUser } = useAuth()
  const gameId = Number(gameIdParam)
  const validGameId = Number.isInteger(gameId) && gameId > 0
  const [game, setGame] = useState<GameApiResponse | null>(null)
  const [homeStat, setHomeStat] = useState<TeamStatApiResponse | null>(null)
  const [awayStat, setAwayStat] = useState<TeamStatApiResponse | null>(null)
  const [existingPrediction, setExistingPrediction] =
    useState<UserPredictionApiResponse | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isLoadingStats, setIsLoadingStats] = useState(false)
  const [isLoadingPrediction, setIsLoadingPrediction] = useState(false)
  const [error, setError] = useState('')
  const [statsError, setStatsError] = useState('')

  useEffect(() => {
    if (!validGameId) {
      setError('올바르지 않은 경기 번호입니다.')
      setIsLoading(false)
      return
    }

    const controller = new AbortController()
    const load = async () => {
      try {
        setIsLoading(true)
        setError('')
        setHomeStat(null)
        setAwayStat(null)

        const response = await apiFetch(`/api/games/${gameId}`, {
          signal: controller.signal,
        })
        if (response.status === 404) {
          throw new Error('경기를 찾을 수 없습니다.')
        }
        if (!response.ok) {
          throw new Error('경기 상세 정보를 불러오지 못했습니다.')
        }

        const data = await response.json() as GameApiResponse
        if (data.id !== gameId) {
          throw new Error('경기 상세 응답이 올바르지 않습니다.')
        }
        if (controller.signal.aborted) return

        setGame(data)
        setIsLoading(false)
        setIsLoadingStats(true)
        const [homeResult, awayResult] = await Promise.allSettled([
          fetchTeamStat(data.homeTeamId, controller.signal),
          fetchTeamStat(data.awayTeamId, controller.signal),
        ])
        if (controller.signal.aborted) return

        setHomeStat(homeResult.status === 'fulfilled' ? homeResult.value : null)
        setAwayStat(awayResult.status === 'fulfilled' ? awayResult.value : null)
        setStatsError(
          homeResult.status === 'rejected' || awayResult.status === 'rejected'
            ? '일부 팀 성적을 불러오지 못했습니다.'
            : '',
        )
      } catch (loadError) {
        if (controller.signal.aborted) return
        setError(
          loadError instanceof Error
            ? loadError.message
            : '경기 상세 정보를 불러오지 못했습니다.',
        )
        setGame(null)
      } finally {
        if (!controller.signal.aborted) {
          setIsLoading(false)
          setIsLoadingStats(false)
        }
      }
    }

    void load()
    return () => controller.abort()
  }, [gameId, validGameId])

  useEffect(() => {
    if (isAuthLoading) return
    if (!user || !validGameId) {
      setExistingPrediction(null)
      setIsLoadingPrediction(false)
      return
    }

    const controller = new AbortController()
    const loadPrediction = async () => {
      try {
        setIsLoadingPrediction(true)
        const response = await apiFetch('/api/user-predictions/me', {
          signal: controller.signal,
        })
        if (response.status === 401) {
          setExistingPrediction(null)
          return
        }
        if (!response.ok) {
          throw new Error('기존 승부예측을 불러오지 못했습니다.')
        }

        const predictions = await response.json() as UserPredictionApiResponse[]
        if (!controller.signal.aborted) {
          setExistingPrediction(
            predictions.find((prediction) => prediction.gameId === gameId) ?? null,
          )
        }
      } catch (predictionError) {
        if (controller.signal.aborted) return
        console.error(predictionError)
        setExistingPrediction(null)
      } finally {
        if (!controller.signal.aborted) setIsLoadingPrediction(false)
      }
    }

    void loadPrediction()
    return () => controller.abort()
  }, [gameId, isAuthLoading, user?.id, validGameId])

  const refreshGame = async () => {
    if (!validGameId) return
    try {
      const response = await apiFetch(`/api/games/${gameId}`)
      if (!response.ok) return
      setGame(await response.json() as GameApiResponse)
    } catch (refreshError) {
      console.error(refreshError)
    }
  }

  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-6 sm:py-8 lg:px-6 lg:py-10">
        <Link
          to="/#games"
          className={cn(buttonVariants({ variant: 'ghost', size: 'sm' }), 'w-fit')}
        >
          <ArrowLeft data-icon="inline-start" />
          경기 목록으로
        </Link>

        {isLoading && (
          <Card>
            <CardContent className="py-20 text-center text-sm text-muted-foreground">
              경기 상세 정보를 불러오는 중입니다.
            </CardContent>
          </Card>
        )}

        {!isLoading && error && (
          <Card>
            <CardContent className="flex flex-col items-center gap-4 py-16 text-center">
              <p className="font-semibold text-destructive">{error}</p>
              <Link
                to="/#games"
                className={cn(buttonVariants({ variant: 'outline' }))}
              >
                경기 목록으로 돌아가기
              </Link>
            </CardContent>
          </Card>
        )}

        {!isLoading && !error && game && (
          <>
            <GameHeader game={game} />

            <div className="grid gap-5 lg:grid-cols-2">
              <AiAnalysis
                game={game}
                homeName={normalizeText(game.homeTeamName, '정보 없음')}
                awayName={normalizeText(game.awayTeamName, '정보 없음')}
              />
              <StartingPitcherComparison
                game={game}
                homeName={normalizeText(game.homeTeamName, '정보 없음')}
                awayName={normalizeText(game.awayTeamName, '정보 없음')}
              />
              <TeamStatComparison
                homeName={normalizeText(game.homeTeamName, '정보 없음')}
                awayName={normalizeText(game.awayTeamName, '정보 없음')}
                homeStat={homeStat}
                awayStat={awayStat}
                isLoading={isLoadingStats}
                error={statsError}
              />
            </div>

            <Card>
              <CardHeader>
                <CardTitle>승부예측</CardTitle>
                <CardDescription>
                  승·무·패를 선택하고 보유 포인트로 참여할 수 있습니다.
                </CardDescription>
                <CardAction>
                  <div className="flex flex-col items-end gap-1">
                    <Badge variant={game.userOdds?.finalized ? 'secondary' : 'outline'}>
                      {game.userOdds?.finalized ? '최종 배당' : '현재 배당'}
                    </Badge>
                    <span className="text-[11px] text-muted-foreground">
                      {formatTotalBetPoints(game.userOdds?.totalBetPoints)}
                    </span>
                  </div>
                </CardAction>
              </CardHeader>
              <CardContent>
                <GamePredictionPanel
                  game={toPredictionGame(game)}
                  existingPrediction={existingPrediction}
                  isExistingPredictionLoading={isLoadingPrediction}
                  onPredictionCreated={(prediction) => {
                    setExistingPrediction(prediction)
                    void refreshUser()
                    void refreshGame()
                  }}
                />
                {isLoadingPrediction && (
                  <p className="mt-3 text-center text-xs text-muted-foreground">
                    기존 예측 내역을 확인하는 중입니다.
                  </p>
                )}
              </CardContent>
            </Card>
          </>
        )}
      </main>
    </div>
  )
}

function GameHeader({ game }: { game: GameApiResponse }) {
  const awayName = normalizeText(game.awayTeamName, '정보 없음')
  const homeName = normalizeText(game.homeTeamName, '정보 없음')
  const showScore = game.status === 'IN_PROGRESS' || game.status === 'FINISHED'

  return (
    <Card className="overflow-hidden">
      <CardHeader className="border-b bg-primary text-primary-foreground">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-primary-foreground/75">
          <span className="flex items-center gap-1.5">
            <CalendarDays className="size-4" />
            {formatGameDate(game.gameDate)}
          </span>
          <span className="flex items-center gap-1.5 font-mono">
            <Clock3 className="size-4" />
            {formatGameTime(game.gameTime)}
          </span>
          <span className="flex items-center gap-1.5">
            <MapPin className="size-4" />
            {normalizeText(game.stadium, '구장 미정')}
          </span>
        </div>
        <CardAction>
          <Badge variant="secondary">{getGameStatusLabel(game.status)}</Badge>
        </CardAction>
      </CardHeader>
      <CardContent className="px-4 py-7 sm:px-8 sm:py-9">
        <div className="grid grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)] items-center gap-3 sm:gap-8">
          <div className="flex min-w-0 flex-col items-center gap-3 text-center">
            <TeamMark teamName={awayName} />
            <div className="min-w-0">
              <p className="text-xs font-semibold text-muted-foreground">원정</p>
              <h1 className="mt-1 break-keep text-base font-black sm:text-2xl">{awayName}</h1>
            </div>
          </div>

          <div className="text-center">
            <p className={showScore
              ? 'font-mono text-2xl font-black tracking-tight sm:text-4xl'
              : 'font-mono text-sm font-bold text-muted-foreground sm:text-base'}>
              {showScore
                ? `${game.awayScore ?? '-'} : ${game.homeScore ?? '-'}`
                : 'VS'}
            </p>
            {game.status === 'CANCELLED' && (
              <p className="mt-2 max-w-32 text-xs font-semibold text-destructive">
                {normalizeText(game.cancelReason, '경기 취소')}
              </p>
            )}
          </div>

          <div className="flex min-w-0 flex-col items-center gap-3 text-center">
            <TeamMark teamName={homeName} />
            <div className="min-w-0">
              <p className="text-xs font-semibold text-muted-foreground">홈</p>
              <h1 className="mt-1 break-keep text-base font-black sm:text-2xl">{homeName}</h1>
            </div>
          </div>
        </div>

        {game.status === 'FINISHED' && game.result == null && (
          <p className="mt-6 text-center text-xs text-muted-foreground">
            표시 점수는 마지막 수집 점수이며 공식 결과 확인 중입니다.
          </p>
        )}
      </CardContent>
    </Card>
  )
}

function toPredictionGame(game: GameApiResponse): PredictionGame {
  return {
    id: game.id,
    home: normalizeText(game.homeTeamName, '정보 없음'),
    away: normalizeText(game.awayTeamName, '정보 없음'),
    status: game.status,
    predictionCloseAt: game.predictionCloseAt,
    userOdds: game.userOdds,
  }
}
