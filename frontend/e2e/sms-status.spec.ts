import { expect, test } from '@playwright/test'
import {
  fillGuestAndSubmit,
  seoulParts,
  selectTomorrowAndHour,
  setAdminDate,
  uniquePhone,
} from './helpers'

test.describe('sms status in admin', () => {
  test('shows sent / waiting / failed based on mock SMS outcomes', async ({ page }) => {
    const tomorrow = seoulParts(1)
    const dayAfter = seoulParts(2)

    // Success path — default WireMock 200
    await page.goto('/')
    await selectTomorrowAndHour(page, '16:00')
    await fillGuestAndSubmit(page, '문자성공', uniquePhone(3000001))
    await expect(page.getByRole('heading', { name: '예약이 완료되었습니다' })).toBeVisible()

    // 503 path — pending retries
    await page.goto('/')
    await selectTomorrowAndHour(page, '17:00')
    await fillGuestAndSubmit(page, '문자대기', '01000000503')
    await expect(page.getByRole('heading', { name: '예약이 완료되었습니다' })).toBeVisible()

    // Blocked path — failed (day+2 to avoid slot conflict)
    await page.goto('/')
    await page.locator('.date-btn').nth(2).click()
    await page.getByRole('button', { name: '10:00' }).click()
    await page.getByRole('button', { name: '다음' }).click()
    await fillGuestAndSubmit(page, '문자실패', '01000000400')
    await expect(page.getByRole('heading', { name: '예약이 완료되었습니다' })).toBeVisible()

    await page.goto('/admin')
    await page.getByLabel('아이디').fill('admin')
    await page.getByLabel('비밀번호').fill('adminpass')
    await page.getByRole('button', { name: '로그인' }).click()
    await expect(page.getByRole('heading', { name: '예약 목록' })).toBeVisible()

    await setAdminDate(page, tomorrow.iso)
    // Wait for worker to process mock SMS on tomorrow
    await expect.poll(async () => page.locator('table').innerText(), { timeout: 20000 }).toMatch(/발송 완료/)
    await expect.poll(async () => page.locator('table').innerText(), { timeout: 20000 }).toMatch(/대기|실패/)

    await setAdminDate(page, dayAfter.iso)
    await expect.poll(async () => page.locator('table').innerText(), { timeout: 20000 }).toMatch(/실패/)
  })
})
