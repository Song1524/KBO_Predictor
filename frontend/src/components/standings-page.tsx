import { ArrowLeft, RefreshCw, Trophy } from 'lucide-react'
import { Link } from 'react-router-dom'
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
import { useStandings } from '@/lib/use-standings'

function formatWinRate(value: number | null) {
  if (value == null) return '-'
  return Number(value).toFixed(3).replace(/^0/, '')
}

function formatGamesBehind(value: number | null) {
  if (value == null) return '-'
  return Number(value) === 0 ? '0' : Number(value).toFixed(1)
}

function formatSnapshotDate(value: string | undefined) {
  if (!value) return null
  return new Date(`${value}T00:00:00+09:00`).toLocaleDateString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  })
}

export function StandingsPage() {
  const { standings, isLoading, error, reload } = useStandings()
  const snapshotDate = formatSnapshotDate(standings[0]?.statDate)

  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-7xl flex-col gap-6 px-4 py-7 lg:px-6 lg:py-10">
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
          <div>
            <Link
              to="/"
              className="mb-4 inline-flex items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground"
            >
              <ArrowLeft className="size-4" aria-hidden="true" />
              홈으로
            </Link>
            <div className="flex items-center gap-3">
              <span className="flex size-11 items-center justify-center rounded-xl bg-primary text-primary-foreground">
                <Trophy className="size-5" aria-hidden="true" />
              </span>
              <div>
                <h1 className="text-3xl font-black tracking-tight">KBO 순위</h1>
                <p className="mt-1 text-sm text-muted-foreground">
                  KBO 공식 정규시즌 팀 순위
                </p>
              </div>
            </div>
          </div>
          {snapshotDate && (
            <Badge variant="outline">{snapshotDate} 수집</Badge>
          )}
        </div>

        <Card>
          <CardHeader className="border-b">
            <CardTitle>전체 팀 순위</CardTitle>
            <CardDescription>
              게임차와 연속 기록은 KBO 공식 순위표의 값을 그대로 표시합니다.
            </CardDescription>
          </CardHeader>
          <CardContent className="px-0">
            {isLoading && (
              <div className="flex min-h-72 items-center justify-center text-sm text-muted-foreground">
                공식 KBO 순위를 불러오는 중입니다.
              </div>
            )}

            {!isLoading && error && (
              <div className="flex min-h-72 flex-col items-center justify-center gap-4 px-4 text-center">
                <p className="text-sm text-destructive">{error}</p>
                <Button variant="outline" onClick={reload}>
                  <RefreshCw data-icon="inline-start" />
                  다시 시도
                </Button>
              </div>
            )}

            {!isLoading && !error && standings.length === 0 && (
              <div className="flex min-h-72 flex-col items-center justify-center gap-2 px-4 text-center">
                <p className="text-sm font-semibold">표시할 순위가 없습니다.</p>
                <p className="text-xs text-muted-foreground">
                  정상 수집된 10팀 순위 스냅샷이 생기면 자동으로 표시됩니다.
                </p>
              </div>
            )}

            {!isLoading && !error && standings.length > 0 && (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[420px] border-collapse text-sm">
                  <caption className="sr-only">KBO 공식 정규시즌 전체 팀 순위</caption>
                  <thead>
                    <tr className="border-b bg-muted/40 text-xs text-muted-foreground">
                      <th className="w-14 px-3 py-3 text-center font-semibold">순위</th>
                      <th className="px-3 py-3 text-left font-semibold">팀</th>
                      <th className="hidden px-3 py-3 text-center font-semibold md:table-cell">경기</th>
                      <th className="hidden px-3 py-3 text-center font-semibold sm:table-cell">승</th>
                      <th className="hidden px-3 py-3 text-center font-semibold sm:table-cell">패</th>
                      <th className="hidden px-3 py-3 text-center font-semibold sm:table-cell">무</th>
                      <th className="px-3 py-3 text-center font-semibold">승률</th>
                      <th className="px-3 py-3 text-center font-semibold">게임차</th>
                      <th className="hidden px-3 py-3 text-center font-semibold lg:table-cell">연속</th>
                    </tr>
                  </thead>
                  <tbody>
                    {standings.map((standing) => (
                      <tr key={standing.teamId} className="border-b last:border-0 hover:bg-muted/30">
                        <td className="px-3 py-4 text-center font-mono text-base font-black">
                          {standing.rank}
                        </td>
                        <td className="px-3 py-4 font-semibold whitespace-nowrap">
                          {standing.teamName}
                        </td>
                        <td className="hidden px-3 py-4 text-center font-mono md:table-cell">{standing.games}</td>
                        <td className="hidden px-3 py-4 text-center font-mono sm:table-cell">{standing.wins}</td>
                        <td className="hidden px-3 py-4 text-center font-mono sm:table-cell">{standing.losses}</td>
                        <td className="hidden px-3 py-4 text-center font-mono sm:table-cell">{standing.draws}</td>
                        <td className="px-3 py-4 text-center font-mono font-bold">{formatWinRate(standing.winRate)}</td>
                        <td className="px-3 py-4 text-center font-mono">{formatGamesBehind(standing.gamesBehind)}</td>
                        <td className="hidden px-3 py-4 text-center lg:table-cell">{standing.streak ?? '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      </main>
      <footer className="border-t">
        <div className="mx-auto max-w-7xl px-4 py-6 text-xs text-muted-foreground lg:px-6">
          PLAYBALL · KBO 공식 정규시즌 팀 순위
        </div>
      </footer>
    </div>
  )
}
