import { useEffect, useState, type FormEvent } from 'react'
import { Bell, CircleUserRound, Gift, Search, ShieldCheck } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth-context'
import { Button, buttonVariants } from '@/components/ui/button'
import type { TeamApiResponse } from '@/lib/api-types'
import { apiFetch } from '@/lib/api-client'
import { cn } from '@/lib/utils'

type AuthMode = 'login' | 'signup'

export function AppHeader() {
  const { user, isAuthenticating, login, signup, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [isAuthOpen, setIsAuthOpen] = useState(false)
  const [mode, setMode] = useState<AuthMode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [nickname, setNickname] = useState('')
  const [favoriteTeamId, setFavoriteTeamId] = useState('')
  const [teams, setTeams] = useState<TeamApiResponse[]>([])
  const [teamsError, setTeamsError] = useState('')
  const [authError, setAuthError] = useState('')
  const [loginBonusPoints, setLoginBonusPoints] = useState(0)

  useEffect(() => {
    if (loginBonusPoints <= 0) return

    const timer = window.setTimeout(() => setLoginBonusPoints(0), 3500)
    return () => window.clearTimeout(timer)
  }, [loginBonusPoints])

  useEffect(() => {
    if (!isAuthOpen || mode !== 'signup' || teams.length > 0) return

    const controller = new AbortController()
    const loadTeams = async () => {
      try {
        setTeamsError('')
        const response = await apiFetch('/api/teams', {
          signal: controller.signal,
        })
        if (!response.ok) throw new Error('응원팀을 불러오지 못했습니다.')
        setTeams(await response.json() as TeamApiResponse[])
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') return
        console.error(error)
        setTeamsError('응원팀 목록을 불러오지 못했습니다.')
      }
    }

    void loadTeams()
    return () => controller.abort()
  }, [isAuthOpen, mode, teams.length])

  useEffect(() => {
    const openLogin = () => {
      setMode('login')
      setAuthError('')
      setIsAuthOpen(true)
    }
    window.addEventListener('playball:open-login', openLogin)
    return () => window.removeEventListener('playball:open-login', openLogin)
  }, [])

  const switchMode = (nextMode: AuthMode) => {
    setMode(nextMode)
    setAuthError('')
  }

  const closeAndReset = () => {
    setIsAuthOpen(false)
    setPassword('')
    setPasswordConfirm('')
    setAuthError('')
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setAuthError('')

    if (mode === 'login') {
      const { errorMessage, dailyLoginBonusPoints } =
        await login(email, password)
      if (errorMessage) {
        setAuthError(errorMessage)
        return
      }
      setLoginBonusPoints(dailyLoginBonusPoints)
      closeAndReset()
      return
    }

    if (password !== passwordConfirm) {
      setAuthError('비밀번호 확인이 일치하지 않습니다.')
      return
    }

    const errorMessage = await signup({
      email,
      password,
      nickname,
      favoriteTeamId: favoriteTeamId ? Number(favoriteTeamId) : null,
    })
    if (errorMessage) {
      setAuthError(errorMessage)
      return
    }

    closeAndReset()
    setNickname('')
    setFavoriteTeamId('')
    navigate('/')
  }

  return (
    <header className="sticky top-0 z-20 border-b bg-background/95 backdrop-blur">
      {loginBonusPoints > 0 && (
        <div
          role="status"
          className="fixed top-20 left-1/2 z-50 flex -translate-x-1/2 items-center gap-2 whitespace-nowrap rounded-full border bg-background px-4 py-2.5 text-sm font-bold text-primary shadow-lg"
        >
          <Gift className="size-4" />
          오늘의 로그인 보너스 +{loginBonusPoints.toLocaleString()}P
        </div>
      )}
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 lg:px-6">
        <div className="flex items-center gap-8">
          <Link
            to="/"
            className="flex items-center gap-2 font-sans text-xl font-black tracking-tight"
            aria-label="플레이볼 홈"
          >
            <span className="flex size-8 items-center justify-center rounded-lg bg-primary text-xs font-black text-primary-foreground">
              PB
            </span>
            PLAYBALL
          </Link>
          <nav className="hidden items-center gap-6 md:flex" aria-label="주요 메뉴">
            <Link
              to="/#games"
              className={cn(
                'text-sm font-medium hover:text-foreground',
                location.pathname === '/'
                  ? 'font-semibold text-primary'
                  : 'text-muted-foreground',
              )}
            >
              승부예측
            </Link>
            <Link
              to="/standings"
              className={cn(
                'text-sm font-medium hover:text-foreground',
                location.pathname === '/standings'
                  ? 'font-semibold text-primary'
                  : 'text-muted-foreground',
              )}
            >
              순위
            </Link>
            <Link
              to="/rankings"
              className={cn(
                'text-sm font-medium hover:text-foreground',
                location.pathname === '/rankings'
                  ? 'font-semibold text-primary'
                  : 'text-muted-foreground',
              )}
            >
              랭킹
            </Link>
            <Link
              to="/#community"
              className="text-sm font-medium text-muted-foreground hover:text-foreground"
              aria-label="커뮤니티 준비 중 영역으로 이동"
            >
              커뮤니티
            </Link>
          </nav>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="icon" aria-label="검색"><Search /></Button>
          <Button variant="ghost" size="icon" aria-label="알림"><Bell /></Button>
          {user ? (
            <div className="flex items-center gap-1">
              {(user.role === 'ADMIN' || user.role === 'ROLE_ADMIN') && (
                <Link
                  to="/admin"
                  className={cn(buttonVariants({ variant: 'outline', size: 'sm' }))}
                >
                    <ShieldCheck data-icon="inline-start" />
                    <span className="hidden sm:inline">관리자</span>
                </Link>
              )}
              <Link
                to="/mypage"
                className={cn(
                  'flex items-center gap-3 rounded-lg border px-3 py-2 transition-colors hover:bg-muted',
                  location.pathname === '/mypage' && 'bg-muted',
                )}
                aria-label="마이페이지로 이동"
              >
                <CircleUserRound className="size-4" />
                <div className="hidden text-right sm:block">
                  <p className="text-xs font-semibold">{user.nickname}</p>
                  <p className="text-xs text-muted-foreground">
                    {user.point.toLocaleString()} P
                  </p>
                </div>
              </Link>
              <Button
                variant="ghost"
                size="xs"
                disabled={isAuthenticating}
                onClick={() => void logout()}
              >
                로그아웃
              </Button>
            </div>
          ) : (
            <div className="relative">
              <Button
                size="sm"
                disabled={isAuthenticating}
                aria-expanded={isAuthOpen}
                onClick={() => setIsAuthOpen((current) => !current)}
              >
                <CircleUserRound data-icon="inline-start" />
                로그인
              </Button>

              {isAuthOpen && (
                <form
                  className="absolute top-10 right-0 z-30 flex w-80 flex-col gap-3 rounded-xl border bg-background p-4 shadow-lg"
                  onSubmit={handleSubmit}
                >
                  <div className="grid grid-cols-2 rounded-lg bg-muted p-1">
                    <button
                      type="button"
                      className={cn(
                        'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                        mode === 'login' ? 'bg-background shadow-sm' : 'text-muted-foreground',
                      )}
                      onClick={() => switchMode('login')}
                    >
                      로그인
                    </button>
                    <button
                      type="button"
                      className={cn(
                        'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                        mode === 'signup' ? 'bg-background shadow-sm' : 'text-muted-foreground',
                      )}
                      onClick={() => switchMode('signup')}
                    >
                      회원가입
                    </button>
                  </div>

                  <div>
                    <p className="text-sm font-semibold">
                      {mode === 'login' ? '이메일 로그인' : 'PLAYBALL 회원가입'}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      {mode === 'login'
                        ? '가입한 이메일과 비밀번호를 입력해 주세요.'
                        : '가입 즉시 1,000P가 지급되고 로그인됩니다.'}
                    </p>
                  </div>

                  <label className="grid gap-1 text-xs font-medium">
                    이메일
                    <input
                      className="h-9 rounded-md border bg-background px-3 text-sm font-normal"
                      type="email"
                      value={email}
                      maxLength={255}
                      required
                      autoComplete="username"
                      onChange={(event) => setEmail(event.target.value)}
                    />
                  </label>
                  <label className="grid gap-1 text-xs font-medium">
                    비밀번호
                    <input
                      className="h-9 rounded-md border bg-background px-3 text-sm font-normal"
                      type="password"
                      value={password}
                      minLength={mode === 'signup' ? 8 : undefined}
                      maxLength={72}
                      required
                      autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                      onChange={(event) => setPassword(event.target.value)}
                    />
                  </label>

                  {mode === 'signup' && (
                    <>
                      <label className="grid gap-1 text-xs font-medium">
                        비밀번호 확인
                        <input
                          className="h-9 rounded-md border bg-background px-3 text-sm font-normal"
                          type="password"
                          value={passwordConfirm}
                          minLength={8}
                          maxLength={72}
                          required
                          autoComplete="new-password"
                          onChange={(event) => setPasswordConfirm(event.target.value)}
                        />
                      </label>
                      <label className="grid gap-1 text-xs font-medium">
                        닉네임
                        <input
                          className="h-9 rounded-md border bg-background px-3 text-sm font-normal"
                          type="text"
                          value={nickname}
                          minLength={2}
                          maxLength={20}
                          required
                          autoComplete="nickname"
                          onChange={(event) => setNickname(event.target.value)}
                        />
                      </label>
                      <label className="grid gap-1 text-xs font-medium">
                        응원팀 <span className="font-normal text-muted-foreground">(선택)</span>
                        <select
                          className="h-9 rounded-md border bg-background px-3 text-sm font-normal"
                          value={favoriteTeamId}
                          onChange={(event) => setFavoriteTeamId(event.target.value)}
                        >
                          <option value="">선택하지 않음</option>
                          {teams.map((team) => (
                            <option key={team.id} value={team.id}>
                              {team.name}
                            </option>
                          ))}
                        </select>
                      </label>
                      {teamsError && (
                        <p className="text-xs text-destructive">{teamsError}</p>
                      )}
                    </>
                  )}

                  {authError && (
                    <p className="text-xs text-destructive">{authError}</p>
                  )}
                  <Button type="submit" disabled={isAuthenticating}>
                    {isAuthenticating
                      ? '처리 중...'
                      : mode === 'login' ? '로그인' : '가입하고 시작하기'}
                  </Button>
                </form>
              )}
            </div>
          )}
        </div>
      </div>
    </header>
  )
}
