import { expect, test } from '@playwright/test'
import { selectTomorrowAndHour } from './helpers'

test.describe('input validation and errors', () => {
  test('requires name and phone and blocks empty submit messages', async ({ page }) => {
    await page.goto('/')
    await selectTomorrowAndHour(page, '11:00')
    await page.getByRole('button', { name: '예약하기' }).click()
    await expect(page.getByText('이름을 입력해 주세요.')).toBeVisible()
    await expect(page.getByText('연락처를 입력해 주세요.')).toBeVisible()
  })

  test('rejects invalid phone format', async ({ page }) => {
    await page.goto('/')
    await selectTomorrowAndHour(page, '13:00')
    await page.getByLabel('이름').fill('홍길동')
    await page.getByLabel('연락처').fill('1234')
    await page.getByRole('button', { name: '예약하기' }).click()
    await expect(page.getByText('올바른 휴대전화 번호를 입력해 주세요.')).toBeVisible()
  })

  test('next is disabled without time selection', async ({ page }) => {
    await page.goto('/')
    await page.locator('.date-btn').nth(1).click()
    await expect(page.getByRole('button', { name: '다음' })).toBeDisabled()
  })

  test('closed slot cannot be selected', async ({ page }) => {
    await page.goto('/')
    // Book 14:00 first via API through UI
    await selectTomorrowAndHour(page, '14:00')
    await page.getByLabel('이름').fill('마감테스트')
    await page.getByLabel('연락처').fill('01081112222')
    await page.getByRole('button', { name: '예약하기' }).click()
    await expect(page.getByRole('heading', { name: '예약이 완료되었습니다' })).toBeVisible()
    await page.getByRole('button', { name: '처음으로' }).click()
    await page.locator('.date-btn').nth(1).click()
    const closed = page.getByRole('button', { name: '14:00 마감' })
    await expect(closed).toBeDisabled()
  })

  test('empty search shows empty state', async ({ page }) => {
    await page.goto('/my')
    await page.getByLabel('연락처').fill('01089998888')
    await page.getByRole('button', { name: '조회하기' }).click()
    await expect(page.getByText('예약 내역이 없습니다.')).toBeVisible()
  })
})
