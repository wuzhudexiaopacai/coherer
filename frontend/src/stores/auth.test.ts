import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api } from '../api'
import { useAuthStore } from './auth'

describe('auth store', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
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

  it('login sends tenant code and stores tenant identity', async () => {
    const login = vi.spyOn(api, 'login').mockResolvedValue({
      accessToken: 'access',
      refreshToken: 'refresh',
      expiresInSeconds: 900,
      user: {
        id: '1',
        tenantId: 'acme',
        tenantName: 'Acme 物联',
        platformAdmin: true,
        username: 'admin',
        nickname: '租户管理员',
        role: 'ADMIN'
      }
    })
    const store = useAuthStore()

    await store.login('acme', 'admin', 'Tenant@123')

    expect(login).toHaveBeenCalledWith({
      tenantCode: 'acme',
      username: 'admin',
      password: 'Tenant@123'
    })
    expect(store.user?.tenantId).toBe('acme')
    expect(store.isPlatformAdmin).toBe(true)
    expect(localStorage.getItem('coherer.accessToken')).toBe('access')
  })

  it('logout clears tokens, tenant-scoped caches and user', async () => {
    localStorage.setItem('coherer.accessToken', 'a')
    localStorage.setItem('coherer.refreshToken', 'r')
    localStorage.setItem('coherer.projectId', '10')
    localStorage.setItem('coherer.spaceId', '20')
    localStorage.setItem(
      'coherer.user',
      JSON.stringify({ id: '1', username: 'admin', nickname: '管理员', role: 'ADMIN' })
    )
    vi.spyOn(api, 'logout').mockResolvedValue(undefined)
    const store = useAuthStore()

    await store.logout()

    expect(store.user).toBeNull()
    expect(localStorage.getItem('coherer.accessToken')).toBeNull()
    expect(localStorage.getItem('coherer.user')).toBeNull()
    expect(localStorage.getItem('coherer.projectId')).toBeNull()
    expect(localStorage.getItem('coherer.spaceId')).toBeNull()
  })
})
