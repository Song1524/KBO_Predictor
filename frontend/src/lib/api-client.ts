const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

let csrfTokenRequest: Promise<string> | null = null

function readCookie(name: string): string | null {
  const encodedName = `${encodeURIComponent(name)}=`
  const cookie = document.cookie
    .split('; ')
    .find((entry) => entry.startsWith(encodedName))

  return cookie == null
    ? null
    : decodeURIComponent(cookie.slice(encodedName.length))
}

async function getCsrfToken(): Promise<string> {
  const existingToken = readCookie(CSRF_COOKIE_NAME)
  if (existingToken != null) return existingToken

  if (csrfTokenRequest == null) {
    csrfTokenRequest = (async () => {
      const response = await fetch('/api/auth/csrf', {
        credentials: 'include',
      })
      if (!response.ok) {
        throw new Error('CSRF 토큰을 발급받지 못했습니다.')
      }

      const issuedToken = readCookie(CSRF_COOKIE_NAME)
      if (issuedToken == null) {
        throw new Error('CSRF 토큰 쿠키가 없습니다.')
      }
      return issuedToken
    })().finally(() => {
      csrfTokenRequest = null
    })
  }

  return csrfTokenRequest
}

export async function apiFetch(
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> {
  const method = (
    init.method ?? (input instanceof Request ? input.method : 'GET')
  ).toUpperCase()
  const headers = new Headers(init.headers)

  if (UNSAFE_METHODS.has(method)) {
    headers.set(CSRF_HEADER_NAME, await getCsrfToken())
  }

  return fetch(input, {
    ...init,
    credentials: init.credentials ?? 'include',
    headers,
  })
}
