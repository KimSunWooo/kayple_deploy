import { expect, test } from '@playwright/test'
import {
  fillGuestAndSubmit,
  seoulParts,
  selectTomorrowAndHour,
  setAdminDate,
  uniquePhone,
} from './helpers'

test.describe('admin console', () => {
  test('login errors, list with sms status, logout', async ({ page }) => {
    const phone = uniquePhone(2000001)
    const tomorrow = seoulParts(1)
    await page.goto('/')
    await selectTomorrowAndHour(page, '15:00')
    await fillGuestAndSubmit(page, '관리대상', phone)
    await expect(page.getByRole('heading', { name: '예약이 완료되었습니다' })).toBeVisible()

    await page.goto('/admin')
    await expect(page.getByRole('heading', { name: '관리자 로그인' })).toBeVisible()
    await page.getByLabel('아이디').fill('admin')
    await page.getByLabel('비밀번호').fill('wrong')
    await page.getByRole('button', { name: '로그인' }).click()
    await expect(page.getByText('아이디 또는 비밀번호가 올바르지 않습니다.')).toBeVisible()

    await page.getByLabel('비밀번호').fill('adminpass')
    await page.getByRole('button', { name: '로그인' }).click()
    await expect(page.getByRole('heading', { name: '예약 목록' })).toBeVisible()
    await setAdminDate(page, tomorrow.iso)
    await expect(page.getByText(phone)).toBeVisible()
    await expect(page.getByText('확정').first()).toBeVisible()
    // SMS status cell: 발송 완료 or 대기 depending on worker timing
    await expect(page.locator('table')).toContainText(/발송 완료|대기|실패/)

    await page.getByRole('button', { name: '로그아웃' }).click()
    await expect(page.getByRole('heading', { name: '관리자 로그인' })).toBeVisible()
    await page.goto('/admin')
    await expect(page.getByRole('heading', { name: '관리자 로그인' })).toBeVisible()
  })
})
