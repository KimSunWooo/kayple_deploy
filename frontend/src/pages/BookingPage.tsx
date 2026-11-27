import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import {
  api,
  addDays,
  formatKoreanDate,
  seoulToday,
  toIsoDate,
  validateName,
  validatePhone,
  type Reservation,
  type Slot,
} from '../api'

type Step = 'select' | 'form' | 'done'

const DAY_LABELS = ['일', '월', '화', '수', '목', '금', '토']

export function BookingPage() {
  const dates = useMemo(() => {
    const today = seoulToday()
    return Array.from({ length: 7 }, (_, i) => addDays(today, i))
  }, [])

  const [step, setStep] = useState<Step>('select')
  const [selectedDate, setSelectedDate] = useState(toIsoDate(dates[0]))
  const [selectedHour, setSelectedHour] = useState<number | null>(null)
  const [slots, setSlots] = useState<Slot[]>([])
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const [phoneError, setPhoneError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [created, setCreated] = useState<Reservation | null>(null)
  const [availabilityTick, setAvailabilityTick] = useState(0)

  useEffect(() => {
    let cancelled = false
    setError(null)
    api
      .availability(selectedDate)
      .then((data) => {
        if (!cancelled) {
          setSlots(data.slots)
          setSelectedHour((prev) => {
            if (prev == null) return null
            const still = data.slots.find((s) => s.hour === prev && s.available)
            return still ? prev : null
          })
        }
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message)
      })
    return () => {
      cancelled = true
    }
  }, [selectedDate, availabilityTick])

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    const nErr = validateName(name)
    const pErr = validatePhone(phone)
    setNameError(nErr)
    setPhoneError(pErr)
    if (nErr || pErr || selectedHour == null) return

    setLoading(true)
    setError(null)
    try {
      const reservation = await api.createReservation({
        name: name.trim(),
        phone,
        date: selectedDate,
        hour: selectedHour,
      })
      setCreated(reservation)
      setStep('done')
    } catch (err) {
      setError(err instanceof Error ? err.message : '예약에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          상담 예약
        </Link>
        <Link to="/my" className="top-link">
          내 예약 확인
        </Link>
      </header>

      <main className="container">
        {step === 'select' && (
          <div className="card-panel">
            <h1>상담 예약하기</h1>
            <p className="subtitle">날짜와 시간을 선택해 주세요</p>
            {error && <div className="banner-error">{error}</div>}

            <div className="section-title">날짜 선택</div>
            <div className="date-grid">
              {dates.map((d) => {
                const iso = toIsoDate(d)
                return (
                  <button
                    key={iso}
                    type="button"
                    className={`date-btn${iso === selectedDate ? ' selected' : ''}`}
                    onClick={() => setSelectedDate(iso)}
                  >
                    <span className="dow">{DAY_LABELS[d.getDay()]}</span>
                    <span className="dom">
                      {d.getMonth() + 1}.{String(d.getDate()).padStart(2, '0')}
                    </span>
                  </button>
                )
              })}
            </div>

            <div className="section-title">시간 선택</div>
            <div className="time-grid">
              {slots.map((slot) => (
                <button
                  key={slot.hour}
                  type="button"
                  className={`time-btn${selectedHour === slot.hour ? ' selected' : ''}`}
                  disabled={!slot.available}
                  onClick={() => setSelectedHour(slot.hour)}
                >
                  {slot.available
                    ? `${String(slot.hour).padStart(2, '0')}:00`
                    : `${String(slot.hour).padStart(2, '0')}:00 마감`}
                </button>
              ))}
            </div>

            <div className="summary-row">
              <span>선택한 일정</span>
              <strong>
                {selectedHour == null
                  ? '시간을 선택해 주세요'
                  : formatKoreanDate(selectedDate, selectedHour)}
              </strong>
            </div>

            <button
              className="primary-btn"
              type="button"
              disabled={selectedHour == null}
              onClick={() => {
                setError(null)
                setStep('form')
              }}
            >
              다음
            </button>
          </div>
        )}

        {step === 'form' && (
          <div className="card-panel">
            <h1>예약 정보 입력</h1>
            <p className="subtitle">이름과 연락처를 입력해 주세요</p>
            {error && <div className="banner-error">{error}</div>}

            <div className="info-list" style={{ marginTop: 0 }}>
              <div className="info-row">
                <span>상담 일정</span>
                <strong>{formatKoreanDate(selectedDate, selectedHour ?? undefined)}</strong>
              </div>
            </div>

            <form onSubmit={onSubmit}>
              <div className="field">
                <label htmlFor="name">이름</label>
                <input
                  id="name"
                  className={nameError ? 'error' : ''}
                  value={name}
                  onChange={(e) => {
                    setName(e.target.value)
                    setNameError(null)
                  }}
                  placeholder="홍길동"
                />
                {nameError && <div className="field-error">{nameError}</div>}
              </div>
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
                {loading ? '예약 중...' : '예약하기'}
              </button>
              <button
                className="secondary-btn"
                type="button"
                onClick={() => {
                  setError(null)
                  setStep('select')
                }}
              >
                이전
              </button>
            </form>
          </div>
        )}

        {step === 'done' && created && (
          <div className="success-box">
            <div className="success-icon">✓</div>
            <h1>예약이 완료되었습니다</h1>
            <p className="subtitle">상담 일정에 맞춰 방문해 주세요</p>
            <div className="info-list">
              <div className="info-row">
                <span>이름</span>
                <strong>{created.name}</strong>
              </div>
              <div className="info-row">
                <span>연락처</span>
                <strong>{created.phone}</strong>
              </div>
              <div className="info-row">
                <span>상담 일정</span>
                <strong>{formatKoreanDate(created.date, created.hour)}</strong>
              </div>
            </div>
            <button
              className="primary-btn"
              type="button"
              onClick={() => {
                setStep('select')
                setSelectedHour(null)
                setName('')
                setPhone('')
                setCreated(null)
                setError(null)
                // Re-fetch slots so the just-booked hour shows as closed.
                setAvailabilityTick((n) => n + 1)
              }}
            >
              처음으로
            </button>
            <Link to="/my" className="secondary-btn" style={{ display: 'block', textAlign: 'center' }}>
              내 예약 확인
            </Link>
          </div>
        )}
      </main>
    </div>
  )
}
