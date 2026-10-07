import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import type {
  LoginApiResponse,
  SignupRequest,
  UserApiResponse,
} from '@/lib/api-types'
import { apiFetch, markAuthenticationChanged, subscribeAuthenticationFailure } from '@/lib/api-client'
import { logoutSession, type LogoutResult } from '@/lib/auth-session'

type LoginResult = {
  errorMessage: string | null
  dailyLoginBonusPoints: number
}

type RefreshUserOptions = {
  maxAgeMs?: number
}

type AuthContextValue = {
  user: UserApiResponse | null
  isLoading: boolean
  isAuthenticating: boolean
  login: (email: string, password: string) => Promise<LoginResult>
  signup: (request: SignupRequest) => Promise<string | null>
  logout: () => Promise<LogoutResult>
  authError: string | null
  refreshUser: (options?: RefreshUserOptions) => Promise<boolean>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserApiResponse | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isAuthenticating, setIsAuthenticating] = useState(false)
  const [authError, setAuthError] = useState<string | null>(null)
  const authEpochRef = useRef(0)
  const lastSuccessfulRefreshAtRef = useRef(0)
  const activeRefreshRef = useRef<Promise<boolean> | null>(null)

  const refreshUser = useCallback(async (
    options: RefreshUserOptions = {},
  ): Promise<boolean> => {
    const maxAgeMs = Math.max(0, options.maxAgeMs ?? 0)
    if (
      maxAgeMs > 0
      && Date.now() - lastSuccessfulRefreshAtRef.current <= maxAgeMs
    ) {
      return true
    }
    if (activeRefreshRef.current) return activeRefreshRef.current

    const request = (async () => {
      const epoch = authEpochRef.current
      try {
        const response = await apiFetch('/api/auth/me', {
          credentials: 'include',
        })

        if (response.status === 401) {
          return false
        }
        if (!response.ok) {
          throw new Error('사용자 정보를 불러오지 못했습니다.')
        }

        const data: UserApiResponse = await response.json()
        if (epoch !== authEpochRef.current) return false
        lastSuccessfulRefreshAtRef.current = Date.now()
        setUser(data)
        return true
      } catch (error) {
        console.error(error)
        return false
      }
    })()

    activeRefreshRef.current = request
    try {
      return await request
    } finally {
      if (activeRefreshRef.current === request) {
        activeRefreshRef.current = null
      }
    }
  }, [])

  useEffect(() => subscribeAuthenticationFailure((failure) => {
    authEpochRef.current += 1
    lastSuccessfulRefreshAtRef.current = 0
    setUser(null)
    setAuthError(failure === 'SESSION_REVOKED'
      ? '계정 상태 또는 권한이 변경되었습니다. 다시 로그인해 주세요.'
      : failure === 'SESSION_EXPIRED' ? '세션이 만료되었습니다. 다시 로그인해 주세요.' : null)
  }), [])

  useEffect(() => {
    const restoreSession = async () => {
      await refreshUser()
      setIsLoading(false)
    }

    void restoreSession()
  }, [refreshUser])

  const login = async (
    email: string,
    password: string,
  ): Promise<LoginResult> => {
    try {
      setIsAuthenticating(true)
      const response = await apiFetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email, password }),
      })
      const responseBody = await response.json().catch(() => null)

      if (!response.ok) {
        return {
          errorMessage: responseBody?.detail ??
            responseBody?.message ??
            '로그인에 실패했습니다.',
          dailyLoginBonusPoints: 0,
        }
      }

      const loginResponse = responseBody as LoginApiResponse
      authEpochRef.current += 1
      markAuthenticationChanged()
      setAuthError(null)
      lastSuccessfulRefreshAtRef.current = Date.now()
      setUser(loginResponse)
      return {
        errorMessage: null,
        dailyLoginBonusPoints:
          loginResponse.dailyLoginBonusGranted &&
          Number.isFinite(loginResponse.dailyLoginBonusPoints)
            ? loginResponse.dailyLoginBonusPoints
            : 0,
      }
    } catch (error) {
      console.error(error)
      return {
        errorMessage: '로그인 요청 중 오류가 발생했습니다.',
        dailyLoginBonusPoints: 0,
      }
    } finally {
      setIsAuthenticating(false)
    }
  }

  const logout = async () => {
    try {
      setIsAuthenticating(true)
      const result = await logoutSession()
      setAuthError(result.errorMessage)
      if (result.completed) {
        authEpochRef.current += 1
        markAuthenticationChanged()
        lastSuccessfulRefreshAtRef.current = 0
        setUser(null)
      }
      return result
    } finally {
      setIsAuthenticating(false)
    }
  }

  const signup = async (request: SignupRequest): Promise<string | null> => {
    try {
      setIsAuthenticating(true)
      const response = await apiFetch('/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(request),
      })
      const responseBody = await response.json().catch(() => null)

      if (!response.ok) {
        return responseBody?.message ?? '회원가입에 실패했습니다.'
      }

      lastSuccessfulRefreshAtRef.current = Date.now()
      authEpochRef.current += 1
      markAuthenticationChanged()
      setAuthError(null)
      setUser(responseBody as UserApiResponse)
      return null
    } catch (error) {
      console.error(error)
      return '회원가입 요청 중 오류가 발생했습니다.'
    } finally {
      setIsAuthenticating(false)
    }
  }

  return (
    <AuthContext.Provider
      value={{
        user,
        isLoading,
        isAuthenticating,
        login,
        signup,
        logout,
        authError,
        refreshUser,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth는 AuthProvider 안에서 사용해야 합니다.')
  }
  return context
}
