import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '../api'
import type { UserInfo } from '../api/types'
import { clearTokens, getAccessToken, setTokens } from '../auth/token'

const USER_KEY = 'coherer.user'

function readUser(): UserInfo | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as UserInfo
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo | null>(readUser())
  const accessToken = ref<string | null>(getAccessToken())

  const isAuthenticated = computed(() => Boolean(accessToken.value))
  const canWrite = computed(
    () => user.value?.role === 'ADMIN' || user.value?.role === 'OPERATOR'
  )
  const roleLabel = computed(() => {
    switch (user.value?.role) {
      case 'ADMIN':
        return '管理员'
      case 'OPERATOR':
        return '运维'
      case 'VIEWER':
        return '只读'
      default:
        return ''
    }
  })

  async function login(username: string, password: string) {
    const response = await api.login({ username, password })
    setTokens(response.accessToken, response.refreshToken)
    accessToken.value = response.accessToken
    user.value = response.user
    localStorage.setItem(USER_KEY, JSON.stringify(response.user))
  }

  /** 登录后以前缓存的空间可能已越界，按服务端最新用户信息校正。 */
  async function refreshUser() {
    const info = await api.me()
    user.value = info
    localStorage.setItem(USER_KEY, JSON.stringify(info))
  }

  function logout() {
    clearTokens()
    accessToken.value = null
    user.value = null
    localStorage.removeItem(USER_KEY)
  }

  function syncToken() {
    accessToken.value = getAccessToken()
  }

  return {
    user,
    accessToken,
    isAuthenticated,
    canWrite,
    roleLabel,
    login,
    logout,
    refreshUser,
    syncToken
  }
})
