import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from 'react'
import type {
  LoginApiResponse,
  SignupRequest,
  UserApiResponse,
} from '@/lib/api-types'
import { apiFetch } from '@/lib/api-client'

type LoginResult = {
  errorMessage: string | null
  dailyLoginBonusPoints: number
}

type AuthContextValue = {
  user: UserApiResponse | null
  isLoading: boolean
  isAuthenticating: boolean
  login: (email: string, password: string) => Promise<LoginResult>
  signup: (request: SignupRequest) => Promise<string | null>
  logout: () => Promise<void>
  refreshUser: () => Promise<boolean>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserApiResponse | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [isAuthenticating, setIsAuthenticating] = useState(false)

  const refreshUser = async (): Promise<boolean> => {
    try {
      const response = await apiFetch('/api/auth/me', {
        credentials: 'include',
      })

      if (response.status === 401) {
        setUser(null)
        return false
      }
      if (!response.ok) {
        throw new Error('사용자 정보를 불러오지 못했습니다.')
      }

      const data: UserApiResponse = await response.json()
      setUser(data)
      return true
    } catch (error) {
      console.error(error)
      setUser(null)
      return false
    }
  }

  useEffect(() => {
    const restoreSession = async () => {
      await refreshUser()
      setIsLoading(false)
    }

    void restoreSession()
  }, [])

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
      await apiFetch('/api/auth/logout', {
        method: 'POST',
        credentials: 'include',
      })
    } finally {
      setUser(null)
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
