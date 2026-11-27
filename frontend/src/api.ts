export type ApiError = { code: string; message: string }

export type ApiResult<T> = {
  success: boolean
  data: T | null
  error: ApiError | null
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options?.headers as Record<string, string> | undefined),
  }

  const csrf = getCookie('XSRF-TOKEN')
  if (csrf) {
    headers['X-XSRF-TOKEN'] = csrf
  }

  const res = await fetch(url, {
    ...options,
    headers,
    credentials: 'include',
  })

  let body: ApiResult<T> | null = null
  try {
    body = await res.json()
  } catch {
    throw new Error('서버 응답을 처리할 수 없습니다.')
  }

  if (!res.ok || !body?.success) {
    throw new Error(body?.error?.message || '요청 처리 중 오류가 발생했습니다.')
  }
  return body.data as T
}

function getCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'))
  return match ? decodeURIComponent(match[1]) : null
}

export type Slot = {
  hour: number
  available: boolean
  label: string
  reason: string | null
}

export type Reservation = {
  id: number
  name: string
  phone: string
  date: string
  hour: number
  status: string
}

export type AdminReservation = Reservation & {
  bookedSmsStatus: string | null
  cancelledSmsStatus: string | null
}

export const api = {
  availability: (date: string) =>
    request<{ date: string; slots: Slot[] }>(`/api/availability?date=${date}`),

  createReservation: (payload: { name: string; phone: string; date: string; hour: number }) =>
    request<Reservation>('/api/reservations', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  searchReservations: (phone: string) =>
    request<{ reservations: Reservation[] }>('/api/reservations/search', {
      method: 'POST',
      body: JSON.stringify({ phone }),
    }),

  cancelReservation: (id: number, phone: string) =>
    request<Reservation>(`/api/reservations/${id}/cancel`, {
      method: 'POST',
      body: JSON.stringify({ phone }),
    }),

  adminLogin: (username: string, password: string) =>
    request<{ username: string }>('/api/admin/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),

  adminLogout: () =>
    request<{ status: string }>('/api/admin/logout', { method: 'POST' }),

  adminMe: () => request<{ username: string }>('/api/admin/me'),

  adminReservations: (date: string) =>
    request<{ date: string; reservations: AdminReservation[] }>(
      `/api/admin/reservations?date=${date}`,
    ),
}

export function normalizePhone(phone: string): string {
  return phone.replace(/[^0-9]/g, '')
}

export function validateName(name: string): string | null {
  const trimmed = name.trim()
  if (!trimmed) return '이름을 입력해 주세요.'
  if (trimmed.length < 2 || trimmed.length > 50) return '이름은 2자 이상 50자 이하로 입력해 주세요.'
  if (!/^[가-힣a-zA-Z\s]+$/.test(trimmed)) return '이름은 한글 또는 영문만 입력해 주세요.'
  return null
}

export function validatePhone(phone: string): string | null {
  const digits = normalizePhone(phone)
  if (!digits) return '연락처를 입력해 주세요.'
  if (!/^01[016789][0-9]{7,8}$/.test(digits)) return '올바른 휴대전화 번호를 입력해 주세요.'
  return null
}

export function formatKoreanDate(dateStr: string, hour?: number): string {
  const date = new Date(dateStr + 'T00:00:00+09:00')
  const days = ['일', '월', '화', '수', '목', '금', '토']
  const base = `${date.getMonth() + 1}월 ${date.getDate()}일 (${days[date.getDay()]})`
  if (hour === undefined) return base
  return `${base} ${String(hour).padStart(2, '0')}:00`
}

export function seoulToday(): Date {
  const now = new Date()
  const seoul = new Date(now.toLocaleString('en-US', { timeZone: 'Asia/Seoul' }))
  return new Date(seoul.getFullYear(), seoul.getMonth(), seoul.getDate())
}

export function addDays(date: Date, days: number): Date {
  const d = new Date(date)
  d.setDate(d.getDate() + days)
  return d
}

export function toIsoDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}
