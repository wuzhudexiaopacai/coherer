import axios, { type AxiosRequestConfig } from 'axios'
import { getAccessToken, getRefreshToken, setTokens, clearTokens } from '../auth/token'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 0) {
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      return body.data
    }
    return body
  },
  async (error) => {
    const config = error?.config as (AxiosRequestConfig & { _retry?: boolean }) | undefined
    const isAuthPath = config?.url?.startsWith('/auth/')

    if (error?.response?.status === 401 && config && !config._retry && !isAuthPath) {
      const refreshToken = getRefreshToken()
      if (refreshToken) {
        config._retry = true
        try {
          const refreshResponse = await axios.post('/api/auth/refresh', { refreshToken })
          const data = refreshResponse.data?.data
          if (!data?.accessToken || !data?.refreshToken) {
            throw new Error('刷新令牌失败')
          }
          setTokens(data.accessToken, data.refreshToken)
          config.headers = {
            ...(config.headers ?? {}),
            Authorization: `Bearer ${data.accessToken}`
          }
          return http.request(config)
        } catch {
          clearTokens()
          if (window.location.pathname !== '/login') {
            window.location.href = '/login'
          }
          return Promise.reject(new Error('登录已过期，请重新登录'))
        }
      }
      clearTokens()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
      return Promise.reject(new Error('登录已过期，请重新登录'))
    }

    const message =
      error?.response?.data?.message || error?.message || '网络异常，请稍后重试'
    return Promise.reject(new Error(message))
  }
)

/** 统一解包 { code, message, data } 响应体。 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  return (await http.request(config)) as unknown as T
}
