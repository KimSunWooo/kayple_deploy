import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import {
  api,
  formatKoreanDate,
  validatePhone,
  type Reservation,
} from '../api'

export function MyReservationsPage() {
  const [phone, setPhone] = useState('')
  const [phoneError, setPhoneError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)
  const [reservations, setReservations] = useState<Reservation[]>([])
  const [cancelTarget, setCancelTarget] = useState<Reservation | null>(null)
  const [cancelling, setCancelling] = useState(false)

  async function onSearch(e: FormEvent) {
    e.preventDefault()
    const pErr = validatePhone(phone)
    setPhoneError(pErr)
    if (pErr) return

    setLoading(true)
    setError(null)
    try {
      const result = await api.searchReservations(phone)
      setReservations(result.reservations)
      setSearched(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : '조회에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  async function confirmCancel() {
    if (!cancelTarget) return
    setCancelling(true)
    setError(null)
    try {
      await api.cancelReservation(cancelTarget.id, phone)
      const result = await api.searchReservations(phone)
      setReservations(result.reservations)
      setCancelTarget(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '취소에 실패했습니다.')
      setCancelTarget(null)
    } finally {
      setCancelling(false)
    }
  }

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          상담 예약
        </Link>
        <Link to="/" className="top-link">
          예약하기
        </Link>
      </header>

      <main className="container">
        <h1>내 예약 확인</h1>
        <p className="subtitle">예약 시 입력한 연락처로 조회합니다</p>
        {error && <div className="banner-error">{error}</div>}

        <form onSubmit={onSearch}>
          <div className="field">
            <label htmlFor="phone">연락처</label>
            <input
              id="phone"
              className={phoneError ? 'error' : ''}
              value={phone}
              onChange={(e) => {
                setPhone(e.target.value)
                setPhoneError(null)
              }}
              placeholder="010-1234-5678"
            />
            {phoneError && <div className="field-error">{phoneError}</div>}
          </div>
          <button className="primary-btn" type="submit" disabled={loading}>
            {loading ? '조회 중...' : '조회하기'}
          </button>
        </form>

        {searched && reservations.length === 0 && (
          <div className="empty-state">예약 내역이 없습니다.</div>
        )}

        {reservations.length > 0 && (
          <div className="reservation-list">
            {reservations.map((r) => (
              <div key={r.id} className="reservation-item">
                <div className="meta">
                  <strong>{formatKoreanDate(r.date, r.hour)}</strong>
                  <span className={`badge ${r.status === 'CONFIRMED' ? 'confirmed' : 'cancelled'}`}>
                    {r.status === 'CONFIRMED' ? '예약 확정' : '취소됨'}
                  </span>
                </div>
                <div style={{ color: 'var(--muted)', fontSize: 14, marginBottom: 12 }}>
                  {r.name} · {r.phone}
                </div>
                {r.status === 'CONFIRMED' && (
                  <button
                    type="button"
                    className="secondary-btn"
                    style={{ marginTop: 0 }}
                    onClick={() => setCancelTarget(r)}
                  >
                    예약 취소
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </main>

      {cancelTarget && (
        <div className="modal-backdrop">
          <div className="modal">
            <h2>예약을 취소할까요?</h2>
            <p>
              {formatKoreanDate(cancelTarget.date, cancelTarget.hour)} 상담 예약이 취소됩니다.
            </p>
            <div className="modal-actions">
              <button
                type="button"
                className="secondary-btn"
                onClick={() => setCancelTarget(null)}
                disabled={cancelling}
              >
                닫기
              </button>
              <button
                type="button"
                className="danger-btn"
                onClick={confirmCancel}
                disabled={cancelling}
              >
                {cancelling ? '취소 중...' : '취소하기'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
