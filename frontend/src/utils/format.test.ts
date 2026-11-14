import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  fmtDateTime,
  fmtNumber,
  fmtRelative,
  fmtTime,
  fmtTimeSec,
  hexToRgba
} from './format'

describe('format utils', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-14T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('returns placeholder for empty values', () => {
    expect(fmtDateTime()).toBe('—')
    expect(fmtTime('')).toBe('—')
    expect(fmtTimeSec(undefined)).toBe('—')
    expect(fmtRelative()).toBe('—')
  })

  it('returns the raw value when date is invalid', () => {
    expect(fmtDateTime('not-a-date')).toBe('not-a-date')
    expect(fmtRelative('2026-13-40')).toBe('2026-13-40')
  })

  it('formats relative time buckets', () => {
    expect(fmtRelative(new Date('2026-11-14T11:59:30Z').toISOString())).toBe('刚刚')
    expect(fmtRelative(new Date('2026-11-14T11:35:00Z').toISOString())).toBe('25 分钟前')
    expect(fmtRelative(new Date('2026-11-14T09:00:00Z').toISOString())).toBe('3 小时前')
    expect(fmtRelative(new Date('2026-11-12T12:00:00Z').toISOString())).toBe('2 天前')
  })

  it('formats numbers with locale separators', () => {
    expect(fmtNumber(1234567)).toBe('1,234,567')
    expect(fmtNumber(0)).toBe('0')
    expect(fmtNumber()).toBe('0')
  })

  it('converts hex colors to rgba', () => {
    expect(hexToRgba('#2fb457', 0.5)).toBe('rgba(47, 180, 87, 0.5)')
    expect(hexToRgba('#fff', 1)).toBe('rgba(255, 255, 255, 1)')
  })
})
