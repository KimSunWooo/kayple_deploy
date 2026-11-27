import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import {
  api,
  formatKoreanDate,
  seoulToday,
  toIsoDate,
  type AdminReservation,
} from '../api'

export function AdminPage() {
  const [authed, setAuthed] = useState<boolean | null>(null)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loginError, setLoginError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [date, setDate] = useState(toIsoDate(seoulToday()))
  const [items, setItems] = useState<AdminReservation[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    api
      .adminMe()
      .then(() => setAuthed(true))
      .catch(() => setAuthed(false))
  }, [])

  useEffect(() => {
    if (!authed) return
    let cancelled = false
    setLoading(true)
    setError(null)
    api
      .adminReservations(date)
      .then((data) => {
        if (!cancelled) setItems(data.reservations)
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [authed, date])

  async function onLogin(e: FormEvent) {
    e.preventDefault()
    setLoginError(null)
    try {
      await api.adminLogin(username, password)
      setAuthed(true)
    } catch (err) {
      setLoginError(err instanceof Error ? err.message : '로그인에 실패했습니다.')
    }
  }

  async function onLogout() {
    try {
      await api.adminLogout()
    } finally {
      setAuthed(false)
    }
  }

  function smsClass(label: string | null) {
    if (!label) return ''
    if (label.includes('완료')) return 'sent'
    if (label.includes('실패')) return 'failed'
    return 'waiting'
  }

  if (authed === null) {
    return <div className="admin-login">로딩 중...</div>
  }

  if (!authed) {
    return (
      <div className="shell">
        <header className="topbar">
          <span className="brand">관리자</span>
          <Link to="/" className="top-link">
            사용자 화면
          </Link>
        </header>
        <main className="admin-login">
          <h1>관리자 로그인</h1>
          <p className="subtitle">예약 목록을 관리하려면 로그인하세요</p>
          {loginError && <div className="banner-error">{loginError}</div>}
          <form onSubmit={onLogin}>
            <div className="field">
              <label htmlFor="username">아이디</label>
              <input
                id="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
              />
            </div>
            <div className="field">
              <label htmlFor="password">비밀번호</label>
              <input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
            </div>
            <button className="primary-btn" type="submit">
              로그인
            </button>
          </form>
        </main>
      </div>
    )
  }

  return (
    <div className="shell admin-shell">
      <header className="topbar">
        <span className="brand">관리자</span>
        <button type="button" className="top-link" style={{ background: 'none', border: 'none' }} onClick={onLogout}>
          로그아웃
        </button>
      </header>
      <main className="container">
        <h1>예약 목록</h1>
        <p className="subtitle">날짜별 예약과 문자 발송 상태를 확인합니다</p>
        {error && <div className="banner-error">{error}</div>}

        <div className="toolbar">
          <label>
            날짜{' '}
            <input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
          </label>
          <span style={{ color: 'var(--muted)' }}>
            {loading ? '불러오는 중...' : `${items.length}건`}
          </span>
        </div>

        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>시간</th>
                <th>이름</th>
                <th>연락처</th>
                <th>상태</th>
                <th>예약 문자</th>
                <th>취소 문자</th>
              </tr>
            </thead>
            <tbody>
              {items.length === 0 ? (
                <tr>
                  <td colSpan={6} style={{ textAlign: 'center', color: 'var(--muted)' }}>
                    해당 날짜의 예약이 없습니다.
                  </td>
                </tr>
              ) : (
                items.map((item) => (
                  <tr key={item.id}>
                    <td>{formatKoreanDate(item.date, item.hour)}</td>
                    <td>{item.name}</td>
                    <td>{item.phone}</td>
                    <td>{item.status === 'CONFIRMED' ? '확정' : '취소'}</td>
                    <td className={`sms-status ${smsClass(item.bookedSmsStatus)}`}>
                      {item.bookedSmsStatus ?? '-'}
                    </td>
                    <td className={`sms-status ${smsClass(item.cancelledSmsStatus)}`}>
                      {item.cancelledSmsStatus ?? '-'}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </main>
    </div>
  )
}
