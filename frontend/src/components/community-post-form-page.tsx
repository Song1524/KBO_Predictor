import { ArrowLeft, FilePenLine, LogIn } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '@/auth-context'
import { AppHeader } from '@/components/app-header'
import { Button, buttonVariants } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import type { CommunityPostApiResponse } from '@/lib/api-types'
import { apiFetch } from '@/lib/api-client'
import { communityApiError, openLoginDialog } from '@/lib/community-utils'
import { cn } from '@/lib/utils'

export function CommunityPostFormPage() {
  const { user, isLoading: isAuthLoading } = useAuth()
  const { postId: postIdParam } = useParams()
  const navigate = useNavigate()
  const isEditing = postIdParam != null
  const postId = Number(postIdParam)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [loadedPost, setLoadedPost] = useState<CommunityPostApiResponse | null>(null)
  const [isLoading, setIsLoading] = useState(isEditing)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!isEditing) return
    if (!Number.isInteger(postId) || postId <= 0) {
      setError('올바르지 않은 게시글입니다.')
      setIsLoading(false)
      return
    }

    const controller = new AbortController()
    const loadPost = async () => {
      try {
        setIsLoading(true)
        setError('')
        const response = await apiFetch(`/api/community/posts/${postId}`, {
          signal: controller.signal,
        })
        if (!response.ok) {
          throw new Error(await communityApiError(
            response,
            '게시글을 불러오지 못했습니다.',
          ))
        }
        const post = await response.json() as CommunityPostApiResponse
        setLoadedPost(post)
        setTitle(post.title)
        setContent(post.content)
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

    void loadPost()
    return () => controller.abort()
  }, [isEditing, postId])

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!user || isSubmitting) return

    try {
      setIsSubmitting(true)
      setError('')
      const response = await apiFetch(
        isEditing
          ? `/api/community/posts/${postId}`
          : '/api/community/posts',
        {
          method: isEditing ? 'PUT' : 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ title, content }),
        },
      )
      if (response.status === 401) {
        openLoginDialog()
        throw new Error('로그인 후 게시글을 작성할 수 있습니다.')
      }
      if (!response.ok) {
        throw new Error(await communityApiError(
          response,
          isEditing
            ? '게시글을 수정하지 못했습니다.'
            : '게시글을 등록하지 못했습니다.',
        ))
      }
      const saved = await response.json() as CommunityPostApiResponse
      navigate(`/community/posts/${saved.id}`, { replace: true })
    } catch (submitError) {
      console.error(submitError)
      setError(submitError instanceof Error
        ? submitError.message
        : '게시글을 저장하지 못했습니다.')
    } finally {
      setIsSubmitting(false)
    }
  }

  const cancelTarget = isEditing && Number.isInteger(postId)
    ? `/community/posts/${postId}`
    : '/community'
  const cannotEdit = isEditing && loadedPost != null
    && loadedPost.authorId !== user?.id

  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-4xl flex-col gap-5 px-4 py-7 lg:px-6 lg:py-10">
        <Link
          to={cancelTarget}
          className="inline-flex w-fit items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" />
          {isEditing ? '게시글로 돌아가기' : '커뮤니티로 돌아가기'}
        </Link>

        <div className="flex items-center gap-3">
          <span className="flex size-11 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <FilePenLine className="size-5" aria-hidden="true" />
          </span>
          <div>
            <h1 className="text-2xl font-black tracking-tight sm:text-3xl">
              {isEditing ? '게시글 수정' : '새 게시글'}
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              야구팬들과 나눌 이야기를 작성해 주세요.
            </p>
          </div>
        </div>

        {isAuthLoading || isLoading ? (
          <Card>
            <CardContent className="flex min-h-64 items-center justify-center text-sm text-muted-foreground">
              {isEditing ? '게시글을 불러오는 중입니다.' : '로그인 상태를 확인하는 중입니다.'}
            </CardContent>
          </Card>
        ) : !user ? (
          <Card>
            <CardContent className="flex min-h-64 flex-col items-center justify-center gap-4 text-center">
              <LogIn className="size-8 text-muted-foreground" />
              <div>
                <p className="font-semibold">로그인이 필요합니다.</p>
                <p className="mt-1 text-sm text-muted-foreground">
                  로그인 후 커뮤니티에 글을 작성할 수 있습니다.
                </p>
              </div>
              <Button onClick={openLoginDialog}>로그인</Button>
            </CardContent>
          </Card>
        ) : isEditing && !loadedPost && error ? (
          <Card>
            <CardContent className="flex min-h-64 flex-col items-center justify-center gap-4 text-center">
              <p className="font-semibold">{error}</p>
              <Link
                to="/community"
                className={cn(buttonVariants({ variant: 'outline' }))}
              >
                커뮤니티 목록
              </Link>
            </CardContent>
          </Card>
        ) : cannotEdit ? (
          <Card>
            <CardContent className="flex min-h-64 flex-col items-center justify-center gap-4 text-center">
              <p className="font-semibold">본인이 작성한 게시글만 수정할 수 있습니다.</p>
              <Link
                to={cancelTarget}
                className={cn(buttonVariants({ variant: 'outline' }))}
              >
                게시글로 돌아가기
              </Link>
            </CardContent>
          </Card>
        ) : (
          <Card>
            <CardHeader className="border-b">
              <CardTitle>{isEditing ? '내용 수정' : '내용 작성'}</CardTitle>
              <CardDescription>일반 텍스트로 안전하게 표시됩니다.</CardDescription>
            </CardHeader>
            <CardContent>
              <form className="grid gap-5" onSubmit={handleSubmit}>
                <label className="grid gap-2 text-sm font-semibold">
                  제목
                  <input
                    className="h-11 rounded-lg border bg-background px-3 font-normal outline-none transition-shadow focus:ring-3 focus:ring-ring/30"
                    value={title}
                    maxLength={120}
                    required
                    disabled={isSubmitting}
                    placeholder="제목을 입력해 주세요."
                    onChange={(event) => setTitle(event.target.value)}
                  />
                  <span className="text-right text-xs font-normal text-muted-foreground">
                    {title.length}/120
                  </span>
                </label>

                <label className="grid gap-2 text-sm font-semibold">
                  본문
                  <textarea
                    className="min-h-72 resize-y rounded-lg border bg-background p-3 font-normal leading-relaxed outline-none transition-shadow focus:ring-3 focus:ring-ring/30"
                    value={content}
                    maxLength={5000}
                    required
                    disabled={isSubmitting}
                    placeholder="경기와 선수에 대한 이야기를 자유롭게 나눠보세요."
                    onChange={(event) => setContent(event.target.value)}
                  />
                  <span className="text-right text-xs font-normal text-muted-foreground">
                    {content.length.toLocaleString()}/5,000
                  </span>
                </label>

                {error && (
                  <p role="alert" className="rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
                    {error}
                  </p>
                )}

                <div className="flex justify-end gap-2">
                  <Link
                    to={cancelTarget}
                    aria-disabled={isSubmitting}
                    className={cn(
                      buttonVariants({ variant: 'outline' }),
                      isSubmitting && 'pointer-events-none opacity-50',
                    )}
                  >
                    취소
                  </Link>
                  <Button type="submit" disabled={isSubmitting || !title.trim() || !content.trim()}>
                    {isSubmitting
                      ? '저장 중...'
                      : isEditing ? '수정 완료' : '등록'}
                  </Button>
                </div>
              </form>
            </CardContent>
          </Card>
        )}

      </main>
    </div>
  )
}
