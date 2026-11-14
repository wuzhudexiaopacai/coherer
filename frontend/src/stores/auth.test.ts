import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'

describe('auth store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('exposes write permission for ADMIN and OPERATOR only', () => {
    localStorage.setItem(
      'coherer.user',
      JSON.stringify({ id: '1', username: 'admin', nickname: '管理员', role: 'ADMIN' })
    )
    expect(useAuthStore().canWrite).toBe(true)

    setActivePinia(createPinia())
    localStorage.setItem(
      'coherer.user',
      JSON.stringify({ id: '2', username: 'viewer', nickname: '只读', role: 'VIEWER' })
    )
    expect(useAuthStore().canWrite).toBe(false)
  })

  it('maps roles to Chinese labels', () => {
    localStorage.setItem(
      'coherer.user',
      JSON.stringify({ id: '3', username: 'op', nickname: '运维', role: 'OPERATOR' })
    )

    expect(useAuthStore().roleLabel).toBe('运维')
  })

  it('logout clears tokens and user', () => {
    localStorage.setItem('coherer.accessToken', 'a')
    localStorage.setItem('coherer.refreshToken', 'r')
    localStorage.setItem(
      'coherer.user',
      JSON.stringify({ id: '1', username: 'admin', nickname: '管理员', role: 'ADMIN' })
    )
    const store = useAuthStore()

    store.logout()

    expect(store.user).toBeNull()
    expect(localStorage.getItem('coherer.accessToken')).toBeNull()
    expect(localStorage.getItem('coherer.user')).toBeNull()
  })
})
