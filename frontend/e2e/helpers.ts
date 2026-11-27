import { expect, type Page } from '@playwright/test'

const SEOUL = 'Asia/Seoul'

export function seoulParts(offsetDays = 0) {
  const now = new Date()
  const seoul = new Date(now.toLocaleString('en-US', { timeZone: SEOUL }))
  seoul.setDate(seoul.getDate() + offsetDays)
  const y = seoul.getFullYear()
  const m = String(seoul.getMonth() + 1).padStart(2, '0')
  const d = String(seoul.getDate()).padStart(2, '0')
  return { date: seoul, iso: `${y}-${m}-${d}`, month: seoul.getMonth() + 1, day: seoul.getDate() }
}

export function uniquePhone(suffix: number) {
  return `0108${String(suffix).padStart(7, '0')}`
}

export async function selectTomorrowAndHour(page: Page, hourLabel: string) {
  const tomorrow = seoulParts(1)
  const dayButtons = page.locator('.date-btn')
  await expect(dayButtons).toHaveCount(7)
  // second button is tomorrow (index 1)
  await dayButtons.nth(1).click()
  const timeBtn = page.getByRole('button', { name: hourLabel, exact: true })
  await expect(timeBtn).toBeEnabled()
  await timeBtn.click()
  await page.getByRole('button', { name: '다음' }).click()
  return tomorrow
}

export async function fillGuestAndSubmit(page: Page, name: string, phone: string) {
  await page.getByLabel('이름').fill(name)
  await page.getByLabel('연락처').fill(phone)
  await page.getByRole('button', { name: '예약하기' }).click()
}

/** Set admin date filter (input type=date). */
export async function setAdminDate(page: Page, isoDate: string) {
  await page.getByLabel('날짜').fill(isoDate)
}
