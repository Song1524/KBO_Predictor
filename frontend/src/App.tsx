import { Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from '@/auth-context'
import { KboDashboard } from '@/components/kbo-dashboard'
import { MyPage } from '@/components/my-page'
import { AdminPage } from '@/components/admin-page'

function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/" element={<KboDashboard />} />
        <Route path="/mypage" element={<MyPage />} />
        <Route path="/admin" element={<AdminPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  )
}

export default App
