import {
  useEffect,
  useRef,
  useState,
  type PointerEvent,
} from 'react'
import {
  ChevronLeft,
  ChevronRight,
  Flame,
  LockKeyhole,
  MessageCircle,
  Sparkles,
  Trophy,
  Users,
} from 'lucide-react'
import { useAuth } from '@/auth-context'
import { AppHeader } from '@/components/app-header'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
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
import { Separator } from '@/components/ui/separator'
import type {
  PredictionOutcome,
  UserPredictionApiResponse,
} from '@/lib/api-types'
import { cn } from '@/lib/utils'

type OutcomeOddsApiResponse = {
  outcome: PredictionOutcome
  betPoints: number
  userBettingRate: number
  odds: number
}

type GameOddsApiResponse = {
  gameId: number
  totalBetPoints: number
  homeWin: OutcomeOddsApiResponse
  draw: OutcomeOddsApiResponse
  awayWin: OutcomeOddsApiResponse
  bettingOpen: boolean
  finalized: boolean
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
  gameTime: string
  homeTeamId: number
  homeTeamName: string
  awayTeamId: number
  awayTeamName: string
  stadium: string
  status: 'SCHEDULED' | 'IN_PROGRESS' | 'FINISHED' | 'CANCELLED'
  homeScore: number | null
  awayScore: number | null
  winnerTeamId: number | null
  result: PredictionOutcome | null
  predictionCloseAt: string | null
  cancelReason: string | null
  aiPrediction: SystemPredictionApiResponse | null
  userOdds: GameOddsApiResponse
}

type DashboardGame = {
  id: number
  awayTeamId: number
  homeTeamId: number
  time: string
  stadium: string
  status: GameApiResponse['status']
  away: string
  awayCode: string
  awayColor: string
  home: string
  homeCode: string
  homeColor: string
  awayPct: number
  drawPct: number
  homePct: number
  userOdds: GameOddsApiResponse
  pitcherAway: string
  pitcherHome: string
  awayScore: number | null
  homeScore: number | null
}


const teamMeta: Record<string, { code: string; color: string }> = {
  'LG 트윈스': { code: 'LG', color: 'bg-team-red' },
  '한화 이글스': { code: 'HH', color: 'bg-team-orange' },
  '두산 베어스': { code: 'OB', color: 'bg-team-navy' },
  'SSG 랜더스': { code: 'SS', color: 'bg-team-red' },
  '삼성 라이온즈': { code: 'SL', color: 'bg-team-blue' },
  '롯데 자이언츠': { code: 'LT', color: 'bg-team-navy' },
  '키움 히어로즈': { code: 'WO', color: 'bg-team-navy' },
  'KIA 타이거즈': { code: 'HT', color: 'bg-team-red' },
  'KT 위즈': { code: 'KT', color: 'bg-team-navy' },
  'NC 다이노스': { code: 'NC', color: 'bg-team-blue' },
}

function getTeamMeta(teamName: string) {
  return teamMeta[teamName] ?? {
    code: teamName.slice(0, 2).toUpperCase(),
    color: 'bg-team-navy',
  }
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


const standings = [
  ['1', 'KIA', '0.612', '-'], ['2', 'LG', '0.584', '3.5'], ['3', '두산', '0.561', '6.0'], ['4', '삼성', '0.548', '7.5'], ['5', 'SSG', '0.514', '11.5'], ['6', 'NC', '0.506', '12.5'],
]

const posts = [
  { category: '경기분석', title: '오늘 잠실 경기, 불펜 운용이 승부를 가를 듯', author: '야구보는곰', comments: 38, time: '12분 전', hot: true },
  { category: '응원방', title: '류현진 선발 경기 직관 갑니다', author: '독수리날다', comments: 21, time: '24분 전', hot: false },
  { category: '자유', title: '올 시즌 가장 놀라운 신인은 누구인가요?', author: '9회말2아웃', comments: 56, time: '41분 전', hot: true },
]

function TeamMark({ code, color }: { code: string; color: string }) {
  return <div className={cn('flex size-12 items-center justify-center rounded-full font-mono text-sm font-black text-primary-foreground', color)}>{code}</div>
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
    data: OutcomeOddsApiResponse
  }> = [
    { outcome: 'AWAY_WIN', data: game.userOdds.awayWin },
    { outcome: 'DRAW', data: game.userOdds.draw },
    { outcome: 'HOME_WIN', data: game.userOdds.homeWin },
  ]

  return (
    <Card className="transition-shadow hover:shadow-md">
      <CardHeader className="border-b">
        <CardTitle className="flex items-center gap-2">
          <span className="font-mono text-sm">{game.time}</span>
          <Badge variant="secondary">
            {getGameStatusLabel(game.status)}
          </Badge>
        </CardTitle>

        <CardDescription>{game.stadium} 야구장</CardDescription>

        <CardAction>
          <span className="text-xs text-muted-foreground">
            {game.userOdds.totalBetPoints.toLocaleString()}P 참여
          </span>
        </CardAction>
      </CardHeader>

      <CardContent className="flex flex-col gap-5">
        <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-3">
          <div className="flex flex-col items-center gap-2 text-center">
            <TeamMark
              code={game.awayCode}
              color={game.awayColor}
            />
            <strong className="text-base">{game.away}</strong>
            <span className="text-xs text-muted-foreground">
              {game.status === 'FINISHED'
                ? `${game.awayScore ?? 0}점`
                : game.pitcherAway}
            </span>
          </div>

          <div className="font-mono text-xs font-semibold text-muted-foreground">
            VS
          </div>

          <div className="flex flex-col items-center gap-2 text-center">
            <TeamMark
              code={game.homeCode}
              color={game.homeColor}
            />
            <strong className="text-base">{game.home}</strong>
            <span className="text-xs text-muted-foreground">
              {game.status === 'FINISHED'
                ? `${game.homeScore ?? 0}점`
                : game.pitcherHome}
            </span>
          </div>
        </div>

        <div className="rounded-lg bg-muted/60 p-3">
          <p className="mb-2 text-center text-xs font-semibold text-muted-foreground">
            AI 예측 확률
          </p>
          <div className="grid grid-cols-3 gap-2 text-center text-xs">
            <div><strong>{game.awayPct}%</strong><p>{game.away} 승</p></div>
            <div><strong>{game.drawPct}%</strong><p>무승부</p></div>
            <div><strong>{game.homePct}%</strong><p>{game.home} 승</p></div>
          </div>
        </div>

        <div className="grid grid-cols-3 gap-2">
          {outcomeOptions.map(({ outcome, data }) => (
            <Button
              key={outcome}
              className="h-auto min-h-24 flex-col gap-1 px-2 py-3"
              disabled={
                !game.userOdds.bettingOpen ||
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
                사용자 {Math.round(Number(data.userBettingRate))}%
              </span>
              <span className="font-mono text-xs font-bold">
                {Number(data.odds).toFixed(2)}배
              </span>
            </Button>
          ))}
        </div>

        {!game.userOdds.bettingOpen && (
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
  const [selectedDate, setSelectedDate] = useState('2026-08-01')
  const [games, setGames] = useState<DashboardGame[]>([])
  const [isLoadingGames, setIsLoadingGames] = useState(true)
  const [gamesError, setGamesError] = useState('')

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

  const loadGames = async () => {
    try {
      setIsLoadingGames(true)
      setGamesError('')

      const gameResponse = await fetch(`/api/games?date=${selectedDate}`)
      if (!gameResponse.ok) throw new Error('경기 목록을 불러오지 못했습니다.')

      const gameData: GameApiResponse[] = await gameResponse.json()

      const dashboardGames = gameData.map((game): DashboardGame => {
        const aiHome = Number(game.aiPrediction?.homeWinProbability)
        const aiDraw = Number(game.aiPrediction?.drawProbability)
        const aiAway = Number(game.aiPrediction?.awayWinProbability)
        const hasCompleteAiPrediction =
          game.aiPrediction?.homeWinProbability != null &&
          game.aiPrediction.drawProbability != null &&
          game.aiPrediction.awayWinProbability != null &&
          aiHome + aiDraw + aiAway > 0

        const awayMeta = getTeamMeta(game.awayTeamName)
        const homeMeta = getTeamMeta(game.homeTeamName)

        return {
          id: game.id,
          awayTeamId: game.awayTeamId,
          homeTeamId: game.homeTeamId,
          time: game.gameTime.slice(0, 5),
          stadium: game.stadium.replace(/야구장$/, ''),
          status: game.status,
          away: game.awayTeamName,
          awayCode: awayMeta.code,
          awayColor: awayMeta.color,
          home: game.homeTeamName,
          homeCode: homeMeta.code,
          homeColor: homeMeta.color,
          awayPct: hasCompleteAiPrediction ? aiAway : 33.3,
          drawPct: hasCompleteAiPrediction ? aiDraw : 33.4,
          homePct: hasCompleteAiPrediction ? aiHome : 33.3,
          userOdds: game.userOdds,
          pitcherAway: '선발 미정',
          pitcherHome: '선발 미정',
          awayScore: game.awayScore,
          homeScore: game.homeScore,
        }
      })

      setGames(dashboardGames)
    } catch (error) {
      setGames([])
      setGamesError(error instanceof Error ? error.message : '알 수 없는 오류가 발생했습니다.')
    } finally {
      setIsLoadingGames(false)
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
    void loadGames()
  }, [selectedDate])
  const gamesTrackRef = useRef<HTMLDivElement>(null)
  const dragStartRef = useRef({ pointerX: 0, scrollLeft: 0 })
  const hasDraggedRef = useRef(false)
  const [isDragging, setIsDragging] = useState(false)

  const handleDragStart = (event: PointerEvent<HTMLDivElement>) => {
    if (event.pointerType !== 'mouse' || event.button !== 0) return

    const target = event.target as HTMLElement

    // 버튼이나 입력창을 누른 경우에는 드래그를 시작하지 않음
    if (
        target.closest(
            'button, input, a, select, textarea, [role="button"]',
        )
    ) {
      return
    }

    const track = gamesTrackRef.current
    if (!track) return

    dragStartRef.current = {
      pointerX: event.clientX,
      scrollLeft: track.scrollLeft,
    }

    hasDraggedRef.current = false
    setIsDragging(true)
    track.setPointerCapture(event.pointerId)
  }

  const handleDragMove = (event: PointerEvent<HTMLDivElement>) => {
    if (!isDragging || event.pointerType !== 'mouse') return
    const track = gamesTrackRef.current
    if (!track) return

    const distance = event.clientX - dragStartRef.current.pointerX
    if (Math.abs(distance) > 5) hasDraggedRef.current = true
    track.scrollLeft = dragStartRef.current.scrollLeft - distance
  }

  const handleDragEnd = (event: PointerEvent<HTMLDivElement>) => {
    if (!isDragging) return

    const track = gamesTrackRef.current

    if (track?.hasPointerCapture(event.pointerId)) {
      track.releasePointerCapture(event.pointerId)
    }

    setIsDragging(false)
  }

  const scrollGames = (direction: 'previous' | 'next') => {
    const track = gamesTrackRef.current
    if (!track) return

    track.scrollBy({
      left: direction === 'next' ? track.clientWidth * 0.85 : -track.clientWidth * 0.85,
      behavior: 'smooth',
    })
  }

  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-7xl flex-col gap-8 px-4 py-7 lg:px-6 lg:py-10">
        <section className="flex flex-col justify-between gap-5 rounded-2xl bg-primary p-6 text-primary-foreground md:flex-row md:items-end md:p-8">
          <div className="flex max-w-2xl flex-col gap-3">
            <Badge variant="secondary" className="w-fit"><Sparkles data-icon="inline-start" />데이터 기반 KBO 승부예측</Badge>
            <h1 className="text-balance text-3xl font-black tracking-tight md:text-4xl">오늘의 승부, 당신의 선택은?</h1>
            <p className="max-w-xl text-pretty text-sm leading-relaxed text-primary-foreground/70 md:text-base">선발, 타선, 불펜 데이터를 분석한 예측을 확인하고 야구팬들과 함께 오늘의 승자를 맞혀보세요.</p>
          </div>
          <div className="flex shrink-0 items-center gap-4 rounded-xl bg-primary-foreground/10 p-4">
            <div className="flex size-10 items-center justify-center rounded-full bg-accent text-accent-foreground"><Trophy /></div>
            <div><p className="text-xs text-primary-foreground/70">이번 주 내 적중률</p><p className="font-mono text-2xl font-black">72.4%</p></div>
          </div>
        </section>

        <section id="games" className="flex flex-col gap-5">
          <div className="flex flex-col justify-between gap-3 sm:flex-row sm:items-end">
            <div><p className="mb-1 text-sm font-semibold text-accent">{new Date(`${selectedDate}T00:00:00`).toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })}</p><h2 className="text-2xl font-black tracking-tight">오늘의 경기</h2></div>
            <div className="flex items-center gap-2"><input type="date" value={selectedDate} onChange={(event) => setSelectedDate(event.target.value)} className="h-9 rounded-md border bg-background px-3 text-sm" /><Badge variant="outline"><Users data-icon="inline-start" />총 {games.length}경기</Badge></div>
          </div>
          <div className="flex items-center justify-between gap-3">
            <p className="text-sm text-muted-foreground">총 {games.length}경기 · 옆으로 넘겨 모든 경기를 확인하세요</p>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="icon" onClick={() => scrollGames('previous')} aria-label="이전 경기">
                <ChevronLeft />
              </Button>
              <Button variant="outline" size="icon" onClick={() => scrollGames('next')} aria-label="다음 경기">
                <ChevronRight />
              </Button>
            </div>
          </div>
          {isLoadingGames && <Card><CardContent className="py-10 text-center text-sm text-muted-foreground">경기 정보를 불러오는 중입니다.</CardContent></Card>}
          {!isLoadingGames && gamesError && <Card><CardContent className="py-10 text-center text-sm text-destructive">{gamesError}</CardContent></Card>}
          {!isLoadingGames && !gamesError && games.length === 0 && <Card><CardContent className="py-10 text-center text-sm text-muted-foreground">해당 날짜에 경기가 없습니다.</CardContent></Card>}
          <div
            ref={gamesTrackRef}
            className={cn(
              'flex cursor-grab snap-x snap-mandatory gap-4 overflow-x-auto pb-3 select-none [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
              isDragging && 'cursor-grabbing snap-none',
            )}
            aria-label="오늘의 경기 목록"
            onPointerDown={handleDragStart}
            onPointerMove={handleDragMove}
            onPointerUp={handleDragEnd}
            onPointerCancel={handleDragEnd}
            onClickCapture={(event) => {
              if (!hasDraggedRef.current) return
              event.preventDefault()
              event.stopPropagation()
              hasDraggedRef.current = false
            }}
          >
            {games.map((game) => {
              const existingPrediction = userPredictions.find(
                  (prediction) => prediction.gameId === game.id,
              )

              return (
                  <div
                      key={game.id}
                      className="w-[88%] shrink-0 snap-start sm:w-[68%] md:w-[48%] xl:w-[calc((100%-2rem)/3)]"
                  >
                    <GameCard
                        game={game}
                        existingSelectedOutcome={
                            existingPrediction?.selectedOutcome ?? null
                        }
                        onPredictionCreated={() => {
                          void refreshUser()
                          void loadUserPredictions()
                          void loadGames()
                        }}
                    />
                  </div>
              )
            })}
          </div>
        </section>

        <section className="grid gap-5 lg:grid-cols-[0.9fr_1.4fr]">
          <Card id="standings">
            <CardHeader><CardTitle>2026 KBO 순위</CardTitle><CardDescription>정규시즌 팀 순위</CardDescription><CardAction><Button variant="ghost" size="sm">전체보기 <ChevronRight data-icon="inline-end" /></Button></CardAction></CardHeader>
            <CardContent>
              <div className="grid grid-cols-[32px_1fr_64px_48px] gap-2 border-b pb-2 text-xs font-medium text-muted-foreground"><span>순위</span><span>팀</span><span>승률</span><span>게임차</span></div>
              {standings.map(([rank, team, pct, gap]) => <div key={team} className="grid grid-cols-[32px_1fr_64px_48px] items-center gap-2 border-b py-3 last:border-0"><span className={cn('font-mono font-bold', rank === '1' && 'text-accent')}>{rank}</span><strong>{team}</strong><span className="font-mono text-sm">{pct}</span><span className="font-mono text-sm text-muted-foreground">{gap}</span></div>)}
            </CardContent>
          </Card>

          <Card id="community">
            <CardHeader><CardTitle>지금 뜨는 이야기</CardTitle><CardDescription>야구팬들이 나누는 실시간 이야기</CardDescription><CardAction><Button variant="ghost" size="sm">커뮤니티 <ChevronRight data-icon="inline-end" /></Button></CardAction></CardHeader>
            <CardContent className="flex flex-col gap-1">
              {posts.map((post, index) => <div key={post.title}><article className="flex items-center gap-3 py-4"><Avatar><AvatarFallback>{post.author.slice(0, 1)}</AvatarFallback></Avatar><div className="min-w-0 flex-1"><div className="mb-1 flex items-center gap-2"><Badge variant="secondary">{post.category}</Badge>{post.hot && <Flame className="size-4 text-accent" aria-label="인기글" />}</div><h3 className="truncate font-semibold">{post.title}</h3><p className="mt-1 text-xs text-muted-foreground">{post.author} · {post.time}</p></div><div className="flex items-center gap-1 text-xs text-muted-foreground"><MessageCircle className="size-4" />{post.comments}</div></article>{index < posts.length - 1 && <Separator />}</div>)}
            </CardContent>
          </Card>
        </section>

        <section id="teams" className="rounded-2xl border bg-muted p-6">
          <div className="flex flex-col justify-between gap-4 md:flex-row md:items-center"><div className="flex items-center gap-3"><div className="flex size-11 items-center justify-center rounded-xl bg-card text-primary shadow-sm"><LockKeyhole /></div><div><h2 className="font-bold">당신의 예측 기록을 쌓아보세요</h2><p className="text-sm text-muted-foreground">응원팀을 설정하고 적중률, 연속 성공, 시즌 랭킹을 관리할 수 있어요.</p></div></div><Button>무료로 시작하기 <ChevronRight data-icon="inline-end" /></Button></div>
        </section>
      </main>
      <footer className="border-t"><div className="mx-auto flex max-w-7xl flex-col justify-between gap-3 px-4 py-6 text-xs text-muted-foreground sm:flex-row lg:px-6"><p>PLAYBALL · KBO 팬을 위한 데이터 기반 승부예측</p><p>본 예측은 참고용이며 사행성 행위를 조장하지 않습니다.</p></div></footer>
    </div>
  )
}
