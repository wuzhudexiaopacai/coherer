import { beforeEach, describe, expect, it } from 'vitest'
import { clearTokens, getAccessToken, getRefreshToken, setTokens } from './token'

describe('token storage', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('stores and reads access and refresh tokens', () => {
    setTokens('access-token', 'refresh-token')

    expect(getAccessToken()).toBe('access-token')
    expect(getRefreshToken()).toBe('refresh-token')
  })

  it('returns null when no tokens exist', () => {
    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('clears both tokens on logout', () => {
    setTokens('access-token', 'refresh-token')

    clearTokens()

    expect(getAccessToken()).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })
})
