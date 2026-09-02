import {
  ChevronLeft,
  ChevronRight,
  Eye,
  MessageCircle,
  PenLine,
  ThumbsUp,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useAuth } from '@/auth-context'
import { AppHeader } from '@/components/app-header'
import { Button, buttonVariants } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import type {
  CommunityPageApiResponse,
  CommunityPostListItemApiResponse,
} from '@/lib/api-types'
import { apiFetch } from '@/lib/api-client'
import {
  communityApiError,
  formatCommunityListDate,
  openLoginDialog,
} from '@/lib/community-utils'
import { cn } from '@/lib/utils'

const PAGE_SIZE = 15

export function CommunityPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedPage = Number(searchParams.get('page') ?? '1')
  const page = Number.isInteger(requestedPage) && requestedPage > 0
    ? requestedPage - 1
    : 0
  const [result, setResult] = useState<CommunityPageApiResponse<CommunityPostListItemApiResponse> | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [reloadVersion, setReloadVersion] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    const loadPosts = async () => {
      try {
        setIsLoading(true)
        setError('')
        const response = await apiFetch(
          `/api/community/posts?page=${page}&size=${PAGE_SIZE}`,
          { signal: controller.signal },
        )
        if (!response.ok) {
          throw new Error(await communityApiError(
            response,
            '게시글을 불러오지 못했습니다.',
          ))
        }
        setResult(await response.json() as CommunityPageApiResponse<CommunityPostListItemApiResponse>)
      } catch (loadError) {
        if (loadError instanceof DOMException && loadError.name === 'AbortError') return
        console.error(loadError)
        setError(loadError instanceof Error
          ? loadError.message
          : '게시글을 불러오지 못했습니다.')
      } finally {
        if (!controller.signal.aborted) setIsLoading(false)
      }
    }

    void loadPosts()
    return () => controller.abort()
  }, [page, reloadVersion])

  const visiblePages = useMemo(() => {
    const totalPages = result?.totalPages ?? 0
    if (totalPages === 0) return []
    const start = Math.max(0, Math.min(page - 2, totalPages - 5))
    return Array.from(
      { length: Math.min(5, totalPages - start) },
      (_, index) => start + index,
    )
  }, [page, result?.totalPages])

  const changePage = (nextPage: number) => {
    setSearchParams(nextPage === 0 ? {} : { page: String(nextPage + 1) })
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  const startWriting = () => {
    if (!user) {
      setNotice('로그인 후 게시글을 작성할 수 있습니다.')
      openLoginDialog()
      return
    }
    navigate('/community/write')
  }

  const posts = result?.content ?? []

  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-7xl flex-col gap-6 px-4 py-7 lg:px-6 lg:py-10">
        <div className="flex items-end justify-between gap-4">
          <div>
            <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-primary">
              <MessageCircle className="size-4" aria-hidden="true" />
              PLAYBALL COMMUNITY
            </div>
            <h1 className="text-3xl font-black tracking-tight">커뮤니티</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              KBO 팬들과 오늘의 경기 이야기를 나눠보세요.
            </p>
          </div>
          <Button onClick={startWriting}>
            <PenLine data-icon="inline-start" />
            글쓰기
          </Button>
        </div>

        {notice && (
          <div role="status" className="rounded-lg border bg-card px-4 py-3 text-sm">
            {notice}
          </div>
        )}

        <Card className="gap-0 py-0">
          <CardContent className="px-0">
            {isLoading && (
              <div className="flex min-h-72 items-center justify-center text-sm text-muted-foreground">
                커뮤니티 글을 불러오는 중입니다.
              </div>
            )}

            {!isLoading && error && (
              <div className="flex min-h-72 flex-col items-center justify-center gap-4 px-4 text-center">
                <p className="text-sm text-destructive">{error}</p>
                <Button variant="outline" onClick={() => setReloadVersion((current) => current + 1)}>
                  다시 시도
                </Button>
              </div>
            )}

            {!isLoading && !error && posts.length === 0 && (
              <div className="flex min-h-72 flex-col items-center justify-center gap-3 px-4 text-center">
                <span className="flex size-11 items-center justify-center rounded-full bg-muted text-muted-foreground">
                  <MessageCircle className="size-5" aria-hidden="true" />
                </span>
                <div>
                  <p className="font-semibold">아직 작성된 게시글이 없습니다.</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    첫 번째 야구 이야기를 남겨보세요.
                  </p>
                </div>
              </div>
            )}

            {!isLoading && !error && posts.length > 0 && (
              <>
                <div className="hidden md:block">
                  <div className="grid grid-cols-[minmax(0,1fr)_130px_90px_64px_64px_64px] border-b bg-muted/40 px-5 py-3 text-xs font-semibold text-muted-foreground">
                    <span>제목</span>
                    <span>작성자</span>
                    <span>작성일</span>
                    <span className="text-center">조회</span>
                    <span className="text-center">댓글</span>
                    <span className="text-center">추천</span>
                  </div>
                  <div className="divide-y">
                    {posts.map((post) => (
                      <Link
                        key={post.id}
                        to={`/community/posts/${post.id}`}
                        className="grid grid-cols-[minmax(0,1fr)_130px_90px_64px_64px_64px] items-center px-5 py-4 transition-colors hover:bg-muted/30"
                      >
                        <span className="truncate pr-5 font-semibold">{post.title}</span>
                        <span className="truncate text-sm">{post.authorNickname}</span>
                        <span className="text-sm text-muted-foreground">{formatCommunityListDate(post.createdAt)}</span>
                        <span className="text-center font-mono text-sm text-muted-foreground">{post.viewCount}</span>
                        <span className="text-center font-mono text-sm font-semibold">{post.commentCount}</span>
                        <span className="flex items-center justify-center gap-1 font-mono text-sm font-semibold text-primary">
                          <ThumbsUp className="size-3.5" />
                          {post.likeCount}
                        </span>
                      </Link>
                    ))}
                  </div>
                </div>

                <div className="divide-y md:hidden">
                  {posts.map((post) => (
                    <Link
                      key={post.id}
                      to={`/community/posts/${post.id}`}
                      className="block px-4 py-4 transition-colors active:bg-muted/40"
                    >
                      <p className="line-clamp-2 font-semibold leading-snug">{post.title}</p>
                      <div className="mt-2 flex min-w-0 items-center gap-2 text-xs text-muted-foreground">
                        <span className="max-w-28 truncate font-medium text-foreground">{post.authorNickname}</span>
                        <span>{formatCommunityListDate(post.createdAt)}</span>
                        <span className="ml-auto flex items-center gap-1"><Eye className="size-3" />{post.viewCount}</span>
                        <span className="flex items-center gap-1"><MessageCircle className="size-3" />{post.commentCount}</span>
                        <span className="flex items-center gap-1 text-primary"><ThumbsUp className="size-3" />{post.likeCount}</span>
                      </div>
                    </Link>
                  ))}
                </div>
              </>
            )}
          </CardContent>
        </Card>

        {!isLoading && !error && (result?.totalPages ?? 0) > 1 && (
          <nav className="flex items-center justify-center gap-1" aria-label="커뮤니티 페이지">
            <Button
              variant="ghost"
              size="icon"
              aria-label="이전 페이지"
              disabled={result?.first}
              onClick={() => changePage(page - 1)}
            >
              <ChevronLeft />
            </Button>
            {visiblePages.map((pageNumber) => (
              <Button
                key={pageNumber}
                variant={pageNumber === page ? 'default' : 'ghost'}
                size="icon"
                aria-label={`${pageNumber + 1}페이지`}
                aria-current={pageNumber === page ? 'page' : undefined}
                onClick={() => changePage(pageNumber)}
              >
                {pageNumber + 1}
              </Button>
            ))}
            <Button
              variant="ghost"
              size="icon"
              aria-label="다음 페이지"
              disabled={result?.last}
              onClick={() => changePage(page + 1)}
            >
              <ChevronRight />
            </Button>
          </nav>
        )}

        <Link
          to="/"
          className={cn(buttonVariants({ variant: 'ghost' }), 'self-start')}
        >
          <ChevronLeft data-icon="inline-start" />
          경기 화면으로
        </Link>
      </main>
      <footer className="border-t">
        <div className="mx-auto max-w-7xl px-4 py-6 text-xs text-muted-foreground lg:px-6">
          PLAYBALL · KBO 팬 커뮤니티
        </div>
      </footer>
    </div>
  )
}
