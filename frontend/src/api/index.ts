import { request } from './http'
import type {
  Device,
  DeviceQuery,
  Overview,
  PageResult,
  Product,
  RegisterResult,
  Shadow,
  Telemetry,
  ThingModel,
  TrendPoint
} from './types'

export const api = {
  overview: () => request<Overview>({ url: '/dashboard/overview', method: 'get' }),

  telemetryTrend: (hours = 24) =>
    request<TrendPoint[]>({
      url: '/dashboard/telemetry-trend',
      method: 'get',
      params: { hours }
    }),

  recentTelemetry: (limit = 10) =>
    request<Telemetry[]>({
      url: '/dashboard/recent-telemetry',
      method: 'get',
      params: { limit }
    }),

  listProducts: (params: { current?: number; size?: number; keyword?: string } = {}) =>
    request<PageResult<Product>>({
      url: '/products',
      method: 'get',
      params: { current: 1, size: 100, ...params }
    }),

  createProduct: (body: { name: string; category?: string; nodeType?: string; description?: string }) =>
    request<Product>({ url: '/products', method: 'post', data: body }),

  thingModel: (productId: string) =>
    request<ThingModel[]>({
      url: `/products/${productId}/thing-model`,
      method: 'get'
    }),

  listDevices: (params: DeviceQuery) =>
    request<PageResult<Device>>({ url: '/devices', method: 'get', params }),

  getDevice: (id: string) => request<Device>({ url: `/devices/${id}`, method: 'get' }),

  registerDevices: (body: { productId: string; count: number; namePrefix?: string }) =>
    request<RegisterResult[]>({ url: '/devices', method: 'post', data: body }),

  deviceTelemetry: (deviceId: string, limit = 60) =>
    request<Telemetry[]>({
      url: `/devices/${deviceId}/telemetry`,
      method: 'get',
      params: { limit }
    }),

  getShadow: (deviceId: string) =>
    request<Shadow>({ url: `/devices/${deviceId}/shadow`, method: 'get' }),

  updateDesired: (
    deviceId: string,
    body: { params: Record<string, unknown>; version?: string | number }
  ) =>
    request<Shadow>({
      url: `/devices/${deviceId}/shadow/desired`,
      method: 'put',
      data: body
    })
}
