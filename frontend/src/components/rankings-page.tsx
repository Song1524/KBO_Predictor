import { useState } from 'react'
import { BarChart3, Crown, RefreshCw, TrendingUp, WalletCards } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/auth-context'
import { AppHeader } from '@/components/app-header'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import type { RankingEntryApiResponse, RankingType } from '@/lib/api-types'
import { useRankings } from '@/lib/use-rankings'
import { cn } from '@/lib/utils'

const rankingTabs: Array<{
  type: RankingType
  label: string
  shortDescription: string
}> = [
  {
    type: 'TOTAL_POINT',
    label: '전체 포인트',
    shortDescription: '현재 보유 포인트',
  },
  {
    type: 'MONTHLY_PROFIT',
    label: '월간 포인트',
    shortDescription: '이번 달 정산 포인트',
  },
  {
    type: 'WEEKLY_PROFIT',
    label: '주간 포인트',
    shortDescription: '이번 주 정산 포인트',
  },
]

function formatScore(entry: RankingEntryApiResponse, type: RankingType) {
  const score = type === 'TOTAL_POINT'
    ? entry.currentPoint
    : entry.periodProfit
  if (score == null) return '-'
  if (type === 'TOTAL_POINT') return `${score.toLocaleString()}P`
  const sign = score > 0 ? '+' : ''
  return `${sign}${score.toLocaleString()}P`
}

function formatHitRate(value: number | null) {
  return value == null ? '-' : `${Number(value).toFixed(1)}%`
}

function formatPeriod(value: string | null) {
  if (!value) return null
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(new Date(value))
}

function RankMark({ rank }: { rank: number }) {
  const podiumClass = rank === 1
    ? 'bg-amber-100 text-amber-800 ring-amber-200'
    : rank === 2
      ? 'bg-slate-100 text-slate-700 ring-slate-200'
      : rank === 3
        ? 'bg-orange-100 text-orange-800 ring-orange-200'
        : 'text-foreground'

  return (
    <span className={cn(
      'inline-flex size-8 items-center justify-center rounded-full font-mono text-sm font-black',
      rank <= 3 && `ring-1 ${podiumClass}`,
    )}>
      {rank}
    </span>
  )
}

function RankingRow({
  entry,
  type,
  isMine,
}: {
  entry: RankingEntryApiResponse
  type: RankingType
  isMine: boolean
}) {
  return (
    <tr className={cn('border-b last:border-0', isMine && 'bg-primary/5')}>
      <td className="px-3 py-3 text-center"><RankMark rank={entry.rank} /></td>
      <td className="px-3 py-3">
        <div className="flex items-center gap-2">
          <span className="font-semibold whitespace-nowrap">{entry.nickname}</span>
          {isMine && <Badge variant="outline">나</Badge>}
        </div>
      </td>
      <td className={cn(
        'px-3 py-3 text-right font-mono font-bold whitespace-nowrap',
        type !== 'TOTAL_POINT' && (entry.periodProfit ?? 0) > 0 && 'text-primary',
        type !== 'TOTAL_POINT' && (entry.periodProfit ?? 0) < 0 && 'text-destructive',
      )}>
        {formatScore(entry, type)}
      </td>
      <td className="px-3 py-3 text-center font-mono">{entry.predictionCount}</td>
      <td className="px-3 py-3 text-center font-mono">{formatHitRate(entry.hitRate)}</td>
    </tr>
  )
}

export function RankingsPage() {
  const { user, isLoading: isAuthLoading } = useAuth()
  const [selectedType, setSelectedType] = useState<RankingType>('TOTAL_POINT')
  const { data, isLoading, error, reload } = useRankings(
    selectedType,
    !isAuthLoading,
    user?.id ?? null,
  )
  const selectedTab = rankingTabs.find((tab) => tab.type === selectedType)!
  const periodStart = formatPeriod(data?.periodStart ?? null)
  const periodEnd = formatPeriod(data?.periodEndExclusive ?? null)

  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-5xl flex-col gap-6 px-4 py-7 lg:px-6 lg:py-10">
        <section className="flex flex-col gap-4 rounded-2xl bg-primary p-6 text-primary-foreground md:flex-row md:items-end md:justify-between md:p-8">
          <div>
            <Badge variant="secondary" className="mb-3">
              <BarChart3 data-icon="inline-start" />
              PLAYBALL RANKING
            </Badge>
            <h1 className="text-3xl font-black tracking-tight">PLAYBALL 예측 랭킹</h1>
            <p className="mt-2 max-w-2xl text-sm text-primary-foreground/70">
              전체 보유 포인트와 월간·주간 정산 포인트를 분리해 비교합니다.
            </p>
          </div>
          <Link
            to="/"
            className="text-sm font-semibold text-primary-foreground/80 hover:text-primary-foreground"
          >
            승부예측으로 돌아가기
          </Link>
        </section>

        <div className="grid grid-cols-3 gap-2 rounded-xl bg-muted p-1" role="tablist" aria-label="랭킹 종류">
          {rankingTabs.map((tab) => (
            <button
              key={tab.type}
              type="button"
              role="tab"
              aria-selected={selectedType === tab.type}
              className={cn(
                'rounded-lg px-2 py-3 text-xs font-semibold transition-colors sm:text-sm',
                selectedType === tab.type
                  ? 'bg-background text-foreground shadow-sm'
                  : 'text-muted-foreground hover:text-foreground',
              )}
              onClick={() => setSelectedType(tab.type)}
            >
              {tab.label}
            </button>
          ))}
        </div>

        <Card>
          <CardHeader className="border-b">
            <div className="flex items-start gap-3">
              <span className="flex size-10 items-center justify-center rounded-xl bg-muted text-primary">
                {selectedType === 'TOTAL_POINT'
                  ? <WalletCards className="size-5" aria-hidden="true" />
                  : <TrendingUp className="size-5" aria-hidden="true" />}
              </span>
              <div>
                <CardTitle>{selectedTab.label} 랭킹</CardTitle>
                <CardDescription>{selectedTab.shortDescription} 기준 TOP 20</CardDescription>
                {periodStart && periodEnd && (
                  <p className="mt-1 text-xs text-muted-foreground">
                    {periodStart} 00:00부터 {periodEnd} 00:00 직전까지 · Asia/Seoul
                  </p>
                )}
              </div>
            </div>
          </CardHeader>
          <CardContent className="px-0">
            {(isAuthLoading || isLoading) && (
              <div className="flex min-h-72 items-center justify-center px-4 text-sm text-muted-foreground">
                PLAYBALL 랭킹을 불러오는 중입니다.
              </div>
            )}

            {!isAuthLoading && !isLoading && error && (
              <div className="flex min-h-72 flex-col items-center justify-center gap-4 px-4 text-center">
                <p className="text-sm text-destructive">{error}</p>
                <Button variant="outline" onClick={reload}>
                  <RefreshCw data-icon="inline-start" />
                  다시 시도
                </Button>
              </div>
            )}

            {!isAuthLoading && !isLoading && !error && data?.rankings.length === 0 && (
              <div className="flex min-h-72 flex-col items-center justify-center gap-3 px-4 text-center">
                <span className="flex size-11 items-center justify-center rounded-full bg-muted text-muted-foreground">
                  <Crown className="size-5" aria-hidden="true" />
                </span>
                <div>
                  <p className="text-sm font-semibold">아직 집계된 랭킹이 없습니다.</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {selectedType === 'TOTAL_POINT'
                      ? '활성 사용자가 등록되면 순위가 표시됩니다.'
                      : '기간 내 정산 완료된 예측이 생기면 순위가 표시됩니다.'}
                  </p>
                </div>
              </div>
            )}

            {!isAuthLoading && !isLoading && !error && data && data.rankings.length > 0 && (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[560px] border-collapse text-sm">
                  <caption className="sr-only">{selectedTab.label} 사용자 랭킹</caption>
                  <thead>
                    <tr className="border-b bg-muted/40 text-xs text-muted-foreground">
                      <th className="w-16 px-3 py-3 text-center font-semibold">순위</th>
                      <th className="px-3 py-3 text-left font-semibold">닉네임</th>
                      <th className="px-3 py-3 text-right font-semibold">
                        {selectedType === 'TOTAL_POINT' ? '현재 포인트' : '기간 포인트'}
                      </th>
                      <th className="px-3 py-3 text-center font-semibold">예측 수</th>
                      <th className="px-3 py-3 text-center font-semibold">적중률</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rankings.map((entry) => (
                      <RankingRow
                        key={entry.userId}
                        entry={entry}
                        type={selectedType}
                        isMine={entry.userId === user?.id}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>

        {!isAuthLoading && user && !isLoading && !error && data && (
          <Card className="border-primary/20 bg-primary/5">
            <CardHeader>
              <CardTitle>내 순위</CardTitle>
              <CardDescription>
                TOP 20 밖이어도 선택한 기준의 전체 순위로 표시됩니다.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {data.myRanking ? (
                <div className="grid grid-cols-[auto_1fr_auto] items-center gap-4">
                  <RankMark rank={data.myRanking.rank} />
                  <div>
                    <p className="font-semibold">{data.myRanking.nickname}</p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      예측 {data.myRanking.predictionCount}회 · 적중률 {formatHitRate(data.myRanking.hitRate)}
                    </p>
                  </div>
                  <strong className="font-mono text-base">
                    {formatScore(data.myRanking, selectedType)}
                  </strong>
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">
                  {selectedType === 'TOTAL_POINT'
                    ? '내 랭킹 정보를 확인할 수 없습니다.'
                    : '이 기간에 정산 완료된 예측이 없습니다.'}
                </p>
              )}
            </CardContent>
          </Card>
        )}
      </main>
      <footer className="border-t">
        <div className="mx-auto max-w-5xl px-4 py-6 text-xs text-muted-foreground lg:px-6">
          PLAYBALL · 사용자 승부예측 활동 랭킹
        </div>
      </footer>
    </div>
  )
}
