import { useEffect, useMemo, useState } from 'react'
import {
  ArrowLeft,
  CalendarDays,
  CircleUserRound,
  Heart,
  ListChecks,
  Percent,
  Trophy,
  Wallet,
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
import { Separator } from '@/components/ui/separator'
import type {
  PointHistoryApiResponse,
  PredictionOutcome,
  PredictionSettlementStatus,
  UserPredictionApiResponse,
} from '@/lib/api-types'
import { cn } from '@/lib/utils'

const settlementLabels: Record<
  PredictionSettlementStatus,
  {
    label: string
    description: string
    variant: 'default' | 'secondary' | 'destructive' | 'outline'
  }
> = {
  PENDING: {
    label: '정산 대기',
    description: '경기 결과를 기다리고 있습니다.',
    variant: 'outline',
  },
  WON: {
    label: '적중',
    description: '예측에 성공해 보상이 지급되었습니다.',
    variant: 'default',
  },
  LOST: {
    label: '실패',
    description: '예측 결과가 실제 경기 결과와 달랐습니다.',
    variant: 'destructive',
  },
  REFUNDED: {
    label: '환불',
    description: '취소 경기 참여 포인트가 환불되었습니다.',
    variant: 'secondary',
  },
}

function predictionOutcomeLabel(
  outcome: PredictionOutcome,
  prediction: UserPredictionApiResponse,
) {
  switch (outcome) {
    case 'HOME_WIN':
      return `${prediction.homeTeamName} 승`
    case 'DRAW':
      return '무승부'
    case 'AWAY_WIN':
      return `${prediction.awayTeamName} 승`
  }
}

function formatGameDate(value: string) {
  return new Date(`${value}T00:00:00`).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
  })
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function formatPointChange(pointChange: number) {
  const sign = pointChange > 0 ? '+' : ''
  return `${sign}${pointChange.toLocaleString()}P`
}

export function MyPage() {
  const { user, isLoading: isAuthLoading, refreshUser } = useAuth()
  const [predictions, setPredictions] = useState<UserPredictionApiResponse[]>([])
  const [pointHistories, setPointHistories] = useState<PointHistoryApiResponse[]>([])
  const [isLoadingData, setIsLoadingData] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')

  useEffect(() => {
    if (isAuthLoading || !user) {
      setPredictions([])
      setPointHistories([])
      return
    }

    let ignore = false
    const loadMyPage = async () => {
      try {
        setIsLoadingData(true)
        setErrorMessage('')
        const [predictionResponse, pointHistoryResponse] = await Promise.all([
          fetch('/api/user-predictions/me', { credentials: 'include' }),
          fetch('/api/points/me/history', { credentials: 'include' }),
        ])

        if (
          predictionResponse.status === 401 ||
          pointHistoryResponse.status === 401
        ) {
          await refreshUser()
          return
        }
        if (!predictionResponse.ok || !pointHistoryResponse.ok) {
          throw new Error('마이페이지 정보를 불러오지 못했습니다.')
        }

        const [predictionData, pointHistoryData] = await Promise.all([
          predictionResponse.json() as Promise<UserPredictionApiResponse[]>,
          pointHistoryResponse.json() as Promise<PointHistoryApiResponse[]>,
        ])

        if (!ignore) {
          setPredictions(predictionData)
          setPointHistories(pointHistoryData)
        }
      } catch (error) {
        console.error(error)
        if (!ignore) {
          setErrorMessage(
            error instanceof Error
              ? error.message
              : '마이페이지 정보를 불러오지 못했습니다.',
          )
        }
      } finally {
        if (!ignore) setIsLoadingData(false)
      }
    }

    void loadMyPage()
    return () => {
      ignore = true
    }
  }, [isAuthLoading, user?.id])

  const statistics = useMemo(() => {
    const won = predictions.filter(
      (prediction) => prediction.settlementStatus === 'WON',
    ).length
    const lost = predictions.filter(
      (prediction) => prediction.settlementStatus === 'LOST',
    ).length
    const refunded = predictions.filter(
      (prediction) => prediction.settlementStatus === 'REFUNDED',
    ).length
    const rateTargetCount = won + lost

    return {
      total: predictions.length,
      won,
      lost,
      refunded,
      hitRate: rateTargetCount === 0 ? 0 : (won / rateTargetCount) * 100,
    }
  }, [predictions])

  if (isAuthLoading) {
    return (
      <div className="min-h-screen bg-background text-foreground">
        <AppHeader />
        <main className="mx-auto max-w-7xl px-4 py-10 lg:px-6">
          <Card>
            <CardContent className="py-16 text-center text-sm text-muted-foreground">
              로그인 상태를 확인하는 중입니다.
            </CardContent>
          </Card>
        </main>
      </div>
    )
  }

  if (!user) {
    return (
      <div className="min-h-screen bg-background text-foreground">
        <AppHeader />
        <main className="mx-auto flex max-w-2xl px-4 py-16 lg:px-6">
          <Card className="w-full">
            <CardContent className="flex flex-col items-center gap-4 py-12 text-center">
              <div className="flex size-12 items-center justify-center rounded-full bg-muted">
                <CircleUserRound className="size-6 text-muted-foreground" />
              </div>
              <div>
                <h1 className="text-xl font-black">로그인이 필요한 화면입니다</h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  로그인하면 내 예측 결과와 포인트 이용 내역을 확인할 수 있습니다.
                </p>
              </div>
              <Button nativeButton={false} render={<Link to="/" />}>
                <ArrowLeft data-icon="inline-start" />
                메인으로 돌아가기
              </Button>
            </CardContent>
          </Card>
        </main>
      </div>
    )
  }

  const statCards = [
    {
      label: '현재 포인트',
      value: `${user.point.toLocaleString()}P`,
      detail: '사용 가능한 포인트',
      icon: Wallet,
    },
    {
      label: '전체 예측 횟수',
      value: `${statistics.total}회`,
      detail: `실패 ${statistics.lost}회 · 환불 ${statistics.refunded}회`,
      icon: ListChecks,
    },
    {
      label: '적중 횟수',
      value: `${statistics.won}회`,
      detail: '정산 완료된 적중 예측',
      icon: Trophy,
    },
    {
      label: '적중률',
      value: `${statistics.hitRate.toFixed(1)}%`,
      detail: '대기·환불 제외',
      icon: Percent,
    },
  ]

  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-7xl flex-col gap-6 px-4 py-7 lg:px-6 lg:py-10">
        <section className="flex flex-col justify-between gap-4 rounded-2xl bg-primary p-6 text-primary-foreground md:flex-row md:items-end md:p-8">
          <div>
            <Badge variant="secondary" className="mb-3">MY PLAYBALL</Badge>
            <h1 className="text-3xl font-black tracking-tight">마이페이지</h1>
            <p className="mt-2 text-sm text-primary-foreground/70">
              내 승부예측 결과와 포인트 이용 내역을 한곳에서 확인하세요.
            </p>
          </div>
          <Button
            variant="secondary"
            nativeButton={false}
            render={<Link to="/" />}
          >
            <ArrowLeft data-icon="inline-start" />
            승부예측으로 돌아가기
          </Button>
        </section>

        <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {statCards.map(({ label, value, detail, icon: Icon }) => (
            <Card key={label}>
              <CardContent className="flex items-start justify-between gap-4">
                <div>
                  <p className="text-xs font-semibold text-muted-foreground">{label}</p>
                  <p className="mt-2 font-mono text-2xl font-black">{value}</p>
                  <p className="mt-1 text-xs text-muted-foreground">{detail}</p>
                </div>
                <div className="flex size-9 items-center justify-center rounded-lg bg-muted text-primary">
                  <Icon className="size-4" />
                </div>
              </CardContent>
            </Card>
          ))}
        </section>

        <section>
          <Card>
            <CardHeader className="border-b">
              <CardTitle>내 정보</CardTitle>
              <CardDescription>현재 로그인된 사용자 정보입니다.</CardDescription>
            </CardHeader>
            <CardContent className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
              <div><p className="text-xs text-muted-foreground">닉네임</p><p className="mt-1 font-semibold">{user.nickname}</p></div>
              <div><p className="text-xs text-muted-foreground">이메일</p><p className="mt-1 font-semibold">{user.email}</p></div>
              <div><p className="text-xs text-muted-foreground">보유 포인트</p><p className="mt-1 font-mono font-semibold">{user.point.toLocaleString()}P</p></div>
              <div>
                <p className="text-xs text-muted-foreground">응원팀</p>
                <p className="mt-1 flex items-center gap-1 font-semibold">
                  {user.favoriteTeamName ? <Heart className="size-4 text-accent" /> : null}
                  {user.favoriteTeamName ?? '설정된 응원팀 없음'}
                </p>
              </div>
            </CardContent>
          </Card>
        </section>

        {errorMessage && (
          <Card>
            <CardContent className="py-6 text-center text-sm text-destructive">
              {errorMessage}
            </CardContent>
          </Card>
        )}

        <section className="grid gap-6 xl:grid-cols-[1.15fr_0.85fr]">
          <Card>
            <CardHeader className="border-b">
              <CardTitle>내 예측</CardTitle>
              <CardDescription>최신 참여 기록부터 표시합니다.</CardDescription>
              <CardAction><Badge variant="outline">{predictions.length}건</Badge></CardAction>
            </CardHeader>
            <CardContent>
              {isLoadingData ? (
                <p className="py-10 text-center text-sm text-muted-foreground">예측 내역을 불러오는 중입니다.</p>
              ) : predictions.length === 0 ? (
                <p className="py-10 text-center text-sm text-muted-foreground">아직 참여한 예측이 없습니다.</p>
              ) : (
                predictions.map((prediction, index) => {
                  const settlement = settlementLabels[prediction.settlementStatus]
                  return (
                    <div key={prediction.id}>
                      <article className="flex flex-col gap-3 py-4 sm:flex-row sm:items-center sm:justify-between">
                        <div className="min-w-0">
                          <p className="flex items-center gap-1 text-xs text-muted-foreground">
                            <CalendarDays className="size-3.5" />
                            {formatGameDate(prediction.gameDate)}
                          </p>
                          <h3 className="mt-1 truncate font-bold">
                            {prediction.awayTeamName} vs {prediction.homeTeamName}
                          </h3>
                          <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-sm">
                            <span>선택: <strong>{predictionOutcomeLabel(prediction.selectedOutcome, prediction)}</strong></span>
                            <span>참여: <strong className="font-mono">{prediction.pointAmount.toLocaleString()}P</strong></span>
                          </div>
                          <p className="mt-1 text-xs text-muted-foreground">{settlement.description}</p>
                        </div>
                        <Badge variant={settlement.variant}>{settlement.label}</Badge>
                      </article>
                      {index < predictions.length - 1 && <Separator />}
                    </div>
                  )
                })
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="border-b">
              <CardTitle>포인트 내역</CardTitle>
              <CardDescription>처리 후 잔액까지 확인할 수 있습니다.</CardDescription>
              <CardAction><Badge variant="outline">{pointHistories.length}건</Badge></CardAction>
            </CardHeader>
            <CardContent>
              {isLoadingData ? (
                <p className="py-10 text-center text-sm text-muted-foreground">포인트 내역을 불러오는 중입니다.</p>
              ) : pointHistories.length === 0 ? (
                <p className="py-10 text-center text-sm text-muted-foreground">아직 기록된 포인트 내역이 없습니다.</p>
              ) : (
                pointHistories.map((history, index) => (
                  <div key={history.id}>
                    <article className="flex items-center justify-between gap-4 py-4">
                      <div className="min-w-0">
                        <h3 className="truncate font-semibold">{history.description}</h3>
                        <p className="mt-1 text-xs text-muted-foreground">
                          {formatDateTime(history.createdAt)} · 처리 후 {history.balanceAfter.toLocaleString()}P
                        </p>
                      </div>
                      <strong
                        className={cn(
                          'shrink-0 font-mono text-sm',
                          history.pointChange > 0 ? 'text-primary' : 'text-destructive',
                        )}
                      >
                        {formatPointChange(history.pointChange)}
                      </strong>
                    </article>
                    {index < pointHistories.length - 1 && <Separator />}
                  </div>
                ))
              )}
            </CardContent>
          </Card>
        </section>
      </main>
    </div>
  )
}
