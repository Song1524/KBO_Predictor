import {
  ArrowLeft,
  Clock3,
  Eye,
  MessageCircle,
  Pencil,
  Reply as ReplyIcon,
  Send,
  Trash2,
  UserRound,
} from 'lucide-react'
import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '@/auth-context'
import { AppHeader } from '@/components/app-header'
import { Badge } from '@/components/ui/badge'
import { Button, buttonVariants } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'
import type {
  CommunityCommentApiResponse,
  CommunityPostApiResponse,
} from '@/lib/api-types'
import { apiFetch } from '@/lib/api-client'
import {
  communityApiError,
  formatCommunityDateTime,
  isAdmin,
  openLoginDialog,
} from '@/lib/community-utils'
import { cn } from '@/lib/utils'

export function CommunityPostDetailPage() {
  const { user } = useAuth()
  const { postId: postIdParam } = useParams()
  const navigate = useNavigate()
  const postId = Number(postIdParam)
  const [post, setPost] = useState<CommunityPostApiResponse | null>(null)
  const [comments, setComments] = useState<CommunityCommentApiResponse[]>([])
  const [commentContent, setCommentContent] = useState('')
  const [isLoading, setIsLoading] = useState(true)
  const [isSubmittingComment, setIsSubmittingComment] = useState(false)
  const [replyingToId, setReplyingToId] = useState<number | null>(null)
  const [replyContent, setReplyContent] = useState('')
  const [submittingReplyId, setSubmittingReplyId] = useState<number | null>(null)
  const [editingCommentId, setEditingCommentId] = useState<number | null>(null)
  const [editContent, setEditContent] = useState('')
  const [updatingCommentId, setUpdatingCommentId] = useState<number | null>(null)
  const [deletingCommentId, setDeletingCommentId] = useState<number | null>(null)
  const [isDeletingPost, setIsDeletingPost] = useState(false)
  const [confirmPostDelete, setConfirmPostDelete] = useState(false)
  const [error, setError] = useState('')
  const [commentError, setCommentError] = useState('')

  useEffect(() => {
    if (!Number.isInteger(postId) || postId <= 0) {
      setError('삭제되었거나 존재하지 않는 게시글입니다.')
      setIsLoading(false)
      return
    }

    const controller = new AbortController()
    const loadPost = async () => {
      try {
        setIsLoading(true)
        setError('')
        const [postResponse, commentsResponse] = await Promise.all([
          apiFetch(`/api/community/posts/${postId}`, {
            signal: controller.signal,
          }),
          apiFetch(`/api/community/posts/${postId}/comments`, {
            signal: controller.signal,
          }),
        ])
        if (!postResponse.ok) {
          if (postResponse.status === 404) {
            throw new Error('삭제되었거나 존재하지 않는 게시글입니다.')
          }
          throw new Error(await communityApiError(
            postResponse,
            '게시글을 불러오지 못했습니다.',
          ))
        }
        if (!commentsResponse.ok) {
          throw new Error(await communityApiError(
            commentsResponse,
            '댓글을 불러오지 못했습니다.',
          ))
        }
        setPost(await postResponse.json() as CommunityPostApiResponse)
        setComments(await commentsResponse.json() as CommunityCommentApiResponse[])
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
  }, [postId])

  const submitComment = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!user) {
      openLoginDialog()
      setCommentError('로그인 후 댓글을 작성할 수 있습니다.')
      return
    }
    if (!commentContent.trim() || isSubmittingComment) return

    try {
      setIsSubmittingComment(true)
      setCommentError('')
      const response = await apiFetch(
        `/api/community/posts/${postId}/comments`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ content: commentContent }),
        },
      )
      if (response.status === 401) {
        openLoginDialog()
        throw new Error('로그인 후 댓글을 작성할 수 있습니다.')
      }
      if (!response.ok) {
        throw new Error(await communityApiError(
          response,
          '댓글을 등록하지 못했습니다.',
        ))
      }
      const created = await response.json() as CommunityCommentApiResponse
      setComments((current) => [...current, created])
      setPost((current) => current == null
        ? current
        : { ...current, commentCount: current.commentCount + 1 })
      setCommentContent('')
    } catch (submitError) {
      console.error(submitError)
      setCommentError(submitError instanceof Error
        ? submitError.message
        : '댓글을 등록하지 못했습니다.')
    } finally {
      setIsSubmittingComment(false)
    }
  }

  const submitReply = async (
    event: FormEvent<HTMLFormElement>,
    parentCommentId: number,
  ) => {
    event.preventDefault()
    if (!user) {
      openLoginDialog()
      setCommentError('로그인 후 답글을 작성할 수 있습니다.')
      return
    }
    if (!replyContent.trim() || submittingReplyId != null) return

    try {
      setSubmittingReplyId(parentCommentId)
      setCommentError('')
      const response = await apiFetch(
        `/api/community/posts/${postId}/comments`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            content: replyContent,
            parentCommentId,
          }),
        },
      )
      if (response.status === 401) {
        openLoginDialog()
        throw new Error('로그인 후 답글을 작성할 수 있습니다.')
      }
      if (!response.ok) {
        throw new Error(await communityApiError(
          response,
          '답글을 등록하지 못했습니다.',
        ))
      }
      const created = await response.json() as CommunityCommentApiResponse
      setComments((current) => current.map((comment) =>
        comment.id === parentCommentId
          ? { ...comment, replies: [...comment.replies, created] }
          : comment,
      ))
      setPost((current) => current == null
        ? current
        : { ...current, commentCount: current.commentCount + 1 })
      setReplyContent('')
      setReplyingToId(null)
    } catch (submitError) {
      console.error(submitError)
      setCommentError(submitError instanceof Error
        ? submitError.message
        : '답글을 등록하지 못했습니다.')
    } finally {
      setSubmittingReplyId(null)
    }
  }

  const updateComment = async (
    event: FormEvent<HTMLFormElement>,
    commentId: number,
  ) => {
    event.preventDefault()
    if (!editContent.trim() || updatingCommentId != null) return

    try {
      setUpdatingCommentId(commentId)
      setCommentError('')
      const response = await apiFetch(`/api/community/comments/${commentId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: editContent }),
      })
      if (!response.ok) {
        throw new Error(await communityApiError(
          response,
          '댓글을 수정하지 못했습니다.',
        ))
      }
      const updated = await response.json() as CommunityCommentApiResponse
      setComments((current) => current.map((comment) => {
        if (comment.id === commentId) {
          return { ...updated, replies: comment.replies }
        }
        return {
          ...comment,
          replies: comment.replies.map((reply) =>
            reply.id === commentId ? updated : reply,
          ),
        }
      }))
      setEditingCommentId(null)
      setEditContent('')
    } catch (updateError) {
      console.error(updateError)
      setCommentError(updateError instanceof Error
        ? updateError.message
        : '댓글을 수정하지 못했습니다.')
    } finally {
      setUpdatingCommentId(null)
    }
  }

  const deleteComment = async (commentId: number) => {
    if (deletingCommentId != null) return
    try {
      setDeletingCommentId(commentId)
      setCommentError('')
      const response = await apiFetch(`/api/community/comments/${commentId}`, {
        method: 'DELETE',
      })
      if (!response.ok) {
        throw new Error(await communityApiError(
          response,
          '댓글을 삭제하지 못했습니다.',
        ))
      }
      setComments((current) => current.flatMap((comment) => {
        if (comment.id === commentId) {
          if (comment.replies.length === 0) return []
          return [{
            ...comment,
            authorId: null,
            authorNickname: null,
            content: null,
            deleted: true,
            edited: false,
          }]
        }

        const replies = comment.replies.filter((reply) => reply.id !== commentId)
        if (comment.deleted && replies.length === 0) return []
        return [{ ...comment, replies }]
      }))
      setPost((current) => current == null
        ? current
        : { ...current, commentCount: Math.max(0, current.commentCount - 1) })
    } catch (deleteError) {
      console.error(deleteError)
      setCommentError(deleteError instanceof Error
        ? deleteError.message
        : '댓글을 삭제하지 못했습니다.')
    } finally {
      setDeletingCommentId(null)
    }
  }

  const deletePost = async () => {
    if (!confirmPostDelete) {
      setConfirmPostDelete(true)
      return
    }
    try {
      setIsDeletingPost(true)
      setError('')
      const response = await apiFetch(`/api/community/posts/${postId}`, {
        method: 'DELETE',
      })
      if (!response.ok) {
        throw new Error(await communityApiError(
          response,
          '게시글을 삭제하지 못했습니다.',
        ))
      }
      navigate('/community', { replace: true })
    } catch (deleteError) {
      console.error(deleteError)
      setError(deleteError instanceof Error
        ? deleteError.message
        : '게시글을 삭제하지 못했습니다.')
      setConfirmPostDelete(false)
    } finally {
      setIsDeletingPost(false)
    }
  }

  const ownsPost = post?.authorId === user?.id
  const canDeletePost = ownsPost || isAdmin(user)
  const wasEdited = post != null && post.updatedAt !== post.createdAt

  const startReply = (commentId: number) => {
    if (!user) {
      openLoginDialog()
      setCommentError('로그인 후 답글을 작성할 수 있습니다.')
      return
    }
    setEditingCommentId(null)
    setEditContent('')
    setReplyingToId(commentId)
    setReplyContent('')
    setCommentError('')
  }

  const startEditing = (comment: CommunityCommentApiResponse) => {
    if (comment.content == null) return
    setReplyingToId(null)
    setReplyContent('')
    setEditingCommentId(comment.id)
    setEditContent(comment.content)
    setCommentError('')
  }

  const renderComment = (
    comment: CommunityCommentApiResponse,
    isReply = false,
  ): ReactNode => {
    const ownsComment = comment.authorId != null
      && comment.authorId === user?.id
    const canDeleteComment = !comment.deleted
      && (ownsComment || isAdmin(user))
    const isEditingComment = editingCommentId === comment.id

    return (
      <div
        key={comment.id}
        className={cn(
          'py-4',
          isReply && 'ml-3 border-l-2 border-muted pl-3 sm:ml-8 sm:pl-5',
        )}
      >
        {comment.deleted ? (
          <p className="py-2 text-sm italic text-muted-foreground">
            삭제된 댓글입니다.
          </p>
        ) : (
          <>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <p className="truncate text-sm font-bold">
                    {comment.authorNickname}
                  </p>
                  {comment.edited && (
                    <Badge variant="outline" className="h-5 px-1.5 text-[10px]">
                      수정됨
                    </Badge>
                  )}
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  {formatCommunityDateTime(comment.createdAt)}
                </p>
              </div>
              <div className="flex flex-wrap justify-end gap-1">
                {ownsComment && (
                  <Button
                    variant="ghost"
                    size="xs"
                    disabled={updatingCommentId != null || deletingCommentId != null}
                    onClick={() => startEditing(comment)}
                  >
                    수정
                  </Button>
                )}
                {canDeleteComment && (
                  <Button
                    variant="ghost"
                    size="xs"
                    disabled={deletingCommentId != null || updatingCommentId != null}
                    onClick={() => void deleteComment(comment.id)}
                  >
                    <Trash2 data-icon="inline-start" />
                    {deletingCommentId === comment.id ? '삭제 중' : '삭제'}
                  </Button>
                )}
                {!isReply && (
                  <Button
                    variant="ghost"
                    size="xs"
                    disabled={submittingReplyId != null}
                    onClick={() => startReply(comment.id)}
                  >
                    <ReplyIcon data-icon="inline-start" />
                    답글
                  </Button>
                )}
              </div>
            </div>

            {isEditingComment ? (
              <form
                className="mt-3 grid gap-2"
                onSubmit={(event) => void updateComment(event, comment.id)}
              >
                <textarea
                  className="min-h-20 resize-y rounded-lg border bg-background p-3 text-sm leading-6 outline-none focus:ring-3 focus:ring-ring/30"
                  value={editContent}
                  maxLength={1000}
                  disabled={updatingCommentId != null}
                  aria-label="댓글 수정"
                  onChange={(event) => setEditContent(event.target.value)}
                />
                <div className="flex items-center justify-between gap-2">
                  <span className="text-xs text-muted-foreground">
                    {editContent.length.toLocaleString()}/1,000
                  </span>
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={updatingCommentId != null}
                      onClick={() => {
                        setEditingCommentId(null)
                        setEditContent('')
                      }}
                    >
                      취소
                    </Button>
                    <Button
                      type="submit"
                      size="sm"
                      disabled={updatingCommentId != null || !editContent.trim()}
                    >
                      {updatingCommentId === comment.id ? '수정 중' : '수정'}
                    </Button>
                  </div>
                </div>
              </form>
            ) : (
              <p className="mt-3 whitespace-pre-wrap break-words leading-6">
                {comment.content}
              </p>
            )}
          </>
        )}

        {!comment.deleted && !isReply && replyingToId === comment.id && (
          <form
            className="mt-3 ml-3 grid gap-2 border-l-2 border-primary/30 pl-3 sm:ml-8 sm:pl-5"
            onSubmit={(event) => void submitReply(event, comment.id)}
          >
            <label className="text-xs font-semibold" htmlFor={`reply-${comment.id}`}>
              {comment.authorNickname}님에게 답글
            </label>
            <textarea
              id={`reply-${comment.id}`}
              className="min-h-20 resize-y rounded-lg border bg-background p-3 text-sm leading-6 outline-none focus:ring-3 focus:ring-ring/30"
              value={replyContent}
              maxLength={1000}
              disabled={submittingReplyId != null}
              placeholder="답글을 입력해 주세요."
              onChange={(event) => setReplyContent(event.target.value)}
            />
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs text-muted-foreground">
                {replyContent.length.toLocaleString()}/1,000
              </span>
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={submittingReplyId != null}
                  onClick={() => {
                    setReplyingToId(null)
                    setReplyContent('')
                  }}
                >
                  취소
                </Button>
                <Button
                  type="submit"
                  size="sm"
                  disabled={submittingReplyId != null || !replyContent.trim()}
                >
                  {submittingReplyId === comment.id ? '등록 중' : '답글 등록'}
                </Button>
              </div>
            </div>
          </form>
        )}

        {comment.replies.length > 0 && (
          <div className="mt-1">
            {comment.replies.map((reply) => renderComment(reply, true))}
          </div>
        )}
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex max-w-5xl flex-col gap-5 px-4 py-7 lg:px-6 lg:py-10">
        <Link
          to="/community"
          className="inline-flex w-fit items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" />
          커뮤니티 목록
        </Link>

        {isLoading && (
          <Card>
            <CardContent className="flex min-h-72 items-center justify-center text-sm text-muted-foreground">
              게시글을 불러오는 중입니다.
            </CardContent>
          </Card>
        )}

        {!isLoading && error && !post && (
          <Card>
            <CardContent className="flex min-h-72 flex-col items-center justify-center gap-4 text-center">
              <p className="font-semibold">{error}</p>
              <Link
                to="/community"
                className={cn(buttonVariants({ variant: 'outline' }))}
              >
                목록으로 돌아가기
              </Link>
            </CardContent>
          </Card>
        )}

        {!isLoading && post && (
          <>
            <article>
              <Card>
                <CardHeader className="border-b">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="secondary">자유 게시판</Badge>
                    {wasEdited && <Badge variant="outline">수정됨</Badge>}
                  </div>
                  <h1 className="mt-2 text-2xl leading-tight font-black tracking-tight sm:text-3xl">
                    {post.title}
                  </h1>
                  <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-muted-foreground sm:text-sm">
                    <span className="flex items-center gap-1.5 font-semibold text-foreground">
                      <UserRound className="size-3.5" />
                      {post.authorNickname}
                    </span>
                    <span className="flex items-center gap-1.5">
                      <Clock3 className="size-3.5" />
                      {formatCommunityDateTime(post.createdAt)}
                    </span>
                    <span className="flex items-center gap-1.5">
                      <Eye className="size-3.5" />
                      {post.viewCount.toLocaleString()}
                    </span>
                  </div>
                </CardHeader>
                <CardContent>
                  <p className="min-h-44 whitespace-pre-wrap break-words text-[15px] leading-7">
                    {post.content}
                  </p>

                  {(ownsPost || canDeletePost) && (
                    <div className="mt-8 flex flex-wrap justify-end gap-2 border-t pt-4">
                      {ownsPost && (
                        <Link
                          to={`/community/posts/${post.id}/edit`}
                          className={cn(buttonVariants({ variant: 'outline' }))}
                        >
                          <Pencil data-icon="inline-start" />
                          수정
                        </Link>
                      )}
                      {canDeletePost && (
                        <>
                          {confirmPostDelete && (
                            <Button
                              variant="ghost"
                              disabled={isDeletingPost}
                              onClick={() => setConfirmPostDelete(false)}
                            >
                              취소
                            </Button>
                          )}
                          <Button
                            variant="destructive"
                            disabled={isDeletingPost}
                            onClick={() => void deletePost()}
                          >
                            <Trash2 data-icon="inline-start" />
                            {isDeletingPost
                              ? '삭제 중...'
                              : confirmPostDelete ? '삭제 확인' : '삭제'}
                          </Button>
                        </>
                      )}
                    </div>
                  )}
                  {error && (
                    <p role="alert" className="mt-3 text-right text-sm text-destructive">
                      {error}
                    </p>
                  )}
                </CardContent>
              </Card>
            </article>

            <section aria-labelledby="comments-title">
              <Card>
                <CardHeader className="border-b">
                  <CardTitle id="comments-title" className="flex items-center gap-2">
                    <MessageCircle className="size-4" />
                    댓글 {post.commentCount.toLocaleString()}
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  {comments.length === 0 ? (
                    <div className="py-9 text-center">
                      <p className="text-sm font-semibold">아직 댓글이 없습니다.</p>
                      <p className="mt-1 text-xs text-muted-foreground">첫 댓글을 남겨보세요.</p>
                    </div>
                  ) : (
                    <div>
                      {comments.map((comment, index) => (
                        <div key={comment.id}>
                          {renderComment(comment)}
                          {index < comments.length - 1 && <Separator />}
                        </div>
                      ))}
                    </div>
                  )}

                  <Separator className="my-4" />
                  <form className="grid gap-3" onSubmit={submitComment}>
                    <label className="text-sm font-semibold" htmlFor="community-comment">
                      댓글 작성
                    </label>
                    <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
                      <textarea
                        id="community-comment"
                        className="min-h-24 flex-1 resize-y rounded-lg border bg-background p-3 text-sm leading-6 outline-none transition-shadow focus:ring-3 focus:ring-ring/30 disabled:bg-muted"
                        value={commentContent}
                        maxLength={1000}
                        disabled={isSubmittingComment}
                        placeholder={user
                          ? '댓글을 입력해 주세요.'
                          : '로그인 후 댓글을 작성할 수 있습니다.'}
                        onFocus={() => {
                          if (!user) openLoginDialog()
                        }}
                        onChange={(event) => setCommentContent(event.target.value)}
                      />
                      <Button
                        type="submit"
                        className="sm:self-stretch sm:px-4"
                        disabled={isSubmittingComment || !commentContent.trim()}
                      >
                        <Send data-icon="inline-start" />
                        {isSubmittingComment ? '등록 중' : '등록'}
                      </Button>
                    </div>
                    <div className="flex items-center justify-between gap-3">
                      {commentError ? (
                        <p role="alert" className="text-sm text-destructive">{commentError}</p>
                      ) : <span />}
                      <span className="ml-auto text-xs text-muted-foreground">
                        {commentContent.length.toLocaleString()}/1,000
                      </span>
                    </div>
                  </form>
                </CardContent>
              </Card>
            </section>
          </>
        )}
      </main>
    </div>
  )
}
