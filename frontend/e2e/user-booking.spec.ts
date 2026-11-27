import { expect, test } from '@playwright/test'
import { fillGuestAndSubmit, selectTomorrowAndHour, uniquePhone } from './helpers'

test.describe('user booking flow', () => {
  test('book, see closed slot, search, cancel, rebook', async ({ page }) => {
    const phone = uniquePhone(1000001)
    await page.goto('/')
    await expect(page.getByRole('heading', { name: '상담 예약하기' })).toBeVisible()
    await expect(page.locator('.date-btn')).toHaveCount(7)

    await selectTomorrowAndHour(page, '10:00')
    await fillGuestAndSubmit(page, '홍길동', phone)
    await expect(page.getByRole('heading', { name: '예약이 완료되었습니다' })).toBeVisible()
    await expect(page.getByText(phone)).toBeVisible()

    await page.getByRole('button', { name: '처음으로' }).click()
    await page.locator('.date-btn').nth(1).click()
    await expect(page.getByRole('button', { name: '10:00 마감' })).toBeDisabled()

    await page.getByRole('link', { name: '내 예약 확인' }).click()
    await page.getByLabel('연락처').fill(phone)
    await page.getByRole('button', { name: '조회하기' }).click()
    await expect(page.getByText('예약 확정')).toBeVisible()
    await page.getByRole('button', { name: '예약 취소' }).click()
    await expect(page.getByRole('heading', { name: '예약을 취소할까요?' })).toBeVisible()
    await page.getByRole('button', { name: '취소하기' }).click()
    await expect(page.getByText('취소됨')).toBeVisible()

    await page.getByRole('link', { name: '예약하기' }).click()
    await selectTomorrowAndHour(page, '10:00')
    await fillGuestAndSubmit(page, '이영희', uniquePhone(1000002))
    await expect(page.getByRole('heading', { name: '예약이 완료되었습니다' })).toBeVisible()
  })
})
