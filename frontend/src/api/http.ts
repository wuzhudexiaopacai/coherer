import axios, { type AxiosRequestConfig } from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

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
  (error) => {
    const message =
      error?.response?.data?.message || error?.message || '网络异常，请稍后重试'
    return Promise.reject(new Error(message))
  }
)

/** 统一解包 { code, message, data } 响应体。 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  return (await http.request(config)) as unknown as T
}
