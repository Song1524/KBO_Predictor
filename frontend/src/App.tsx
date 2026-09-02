import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from '@/auth-context'
import { KboDashboard } from '@/components/kbo-dashboard'
import { MyPage } from '@/components/my-page'
import { AdminPage } from '@/components/admin-page'
import { StandingsPage } from '@/components/standings-page'
import { RankingsPage } from '@/components/rankings-page'
import { GameDetailPage } from '@/components/game-detail-page'
import { CommunityPage } from '@/components/community-page'
import { CommunityPostDetailPage } from '@/components/community-post-detail-page'
import { CommunityPostFormPage } from '@/components/community-post-form-page'

function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/" element={<KboDashboard />} />
        <Route path="/games/:gameId" element={<GameDetailPage />} />
        <Route path="/mypage" element={<MyPage />} />
        <Route path="/standings" element={<StandingsPage />} />
        <Route path="/rankings" element={<RankingsPage />} />
        <Route path="/admin" element={<AdminPage />} />
        <Route path="/community" element={<CommunityPage />} />
        <Route path="/community/write" element={<CommunityPostFormPage />} />
        <Route
          path="/community/posts/:postId"
          element={<CommunityPostDetailPage />}
        />
        <Route
          path="/community/posts/:postId/edit"
          element={<CommunityPostFormPage />}
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  )
}

export default App
