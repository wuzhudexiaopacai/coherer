import { request } from './http'
import type {
  AlarmQuery,
  AlarmRecord,
  AlarmRule,
  AuthResponse,
  ConditionNode,
  Device,
  DeviceQuery,
  MqttCredential,
  Overview,
  PageResult,
  Product,
  RegisterResult,
  Shadow,
  SpaceNode,
  Telemetry,
  ThingModel,
  TrendPoint,
  UserInfo,
  UserItem,
  CommandRecord,
  AuditLogItem,
  DeviceEvent
} from './types'

export const api = {
  login: (body: { username: string; password: string }) =>
    request<AuthResponse>({ url: '/auth/login', method: 'post', data: body }),

  refresh: (body: { refreshToken: string }) =>
    request<AuthResponse>({ url: '/auth/refresh', method: 'post', data: body }),

  me: () => request<UserInfo>({ url: '/auth/me', method: 'get' }),

  logout: () => request<void>({ url: '/auth/logout', method: 'post' }),

  overview: (spaceId?: string | null) =>
    request<Overview>({
      url: '/dashboard/overview',
      method: 'get',
      params: spaceId ? { spaceId } : {}
    }),

  telemetryTrend: (hours = 24, spaceId?: string | null) =>
    request<TrendPoint[]>({
      url: '/dashboard/telemetry-trend',
      method: 'get',
      params: spaceId ? { hours, spaceId } : { hours }
    }),

  recentTelemetry: (limit = 10, spaceId?: string | null) =>
    request<Telemetry[]>({
      url: '/dashboard/recent-telemetry',
      method: 'get',
      params: spaceId ? { limit, spaceId } : { limit }
    }),

  spaceTree: () => request<SpaceNode[]>({ url: '/spaces/tree', method: 'get' }),

  createSpace: (body: {
    parentId?: string
    name: string
    code?: string
    type?: string
    sort?: number
    description?: string
  }) => request<SpaceNode>({ url: '/spaces', method: 'post', data: body }),

  deleteSpace: (id: string) =>
    request<void>({ url: `/spaces/${id}`, method: 'delete' }),

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

  enableDevice: (id: string) =>
    request<Device>({ url: `/devices/${id}/enable`, method: 'post' }),

  disableDevice: (id: string) =>
    request<Device>({ url: `/devices/${id}/disable`, method: 'post' }),

  rotateDeviceSecret: (id: string) =>
    request<RegisterResult>({ url: `/devices/${id}/rotate-secret`, method: 'post' }),

  deleteDevice: (id: string) =>
    request<void>({ url: `/devices/${id}`, method: 'delete' }),

  registerDevices: (body: {
    productId: string
    count: number
    namePrefix?: string
    spaceId?: string
  }) =>
    request<RegisterResult[]>({ url: '/devices', method: 'post', data: body }),

  listAuditLogs: (params: {
    current?: number
    size?: number
    username?: string
    action?: string
    result?: string
    traceId?: string
  } = {}) => request<PageResult<AuditLogItem>>({ url: '/audit-logs', method: 'get', params }),

  listUsers: (params: { current?: number; size?: number; keyword?: string } = {}) =>
    request<PageResult<UserItem>>({ url: '/users', method: 'get', params }),

  createUser: (body: {
    username: string
    password: string
    nickname: string
    role: string
    spaceId?: string | null
  }) => request<UserItem>({ url: '/users', method: 'post', data: body }),

  updateUser: (
    id: string,
    body: { nickname?: string; role?: string; spaceId?: string | number | null; status?: string }
  ) => request<UserItem>({ url: `/users/${id}`, method: 'put', data: body }),

  resetUserPassword: (id: string, password: string) =>
    request<void>({ url: `/users/${id}/reset-password`, method: 'post', data: { password } }),

  deleteUser: (id: string) => request<void>({ url: `/users/${id}`, method: 'delete' }),

  deviceEvents: (deviceId: string, limit = 20) =>
    request<DeviceEvent[]>({
      url: `/devices/${deviceId}/events`,
      method: 'get',
      params: { limit }
    }),

  sendCommand: (deviceId: string, body: { identifier: string; params?: Record<string, unknown> }) =>
    request<CommandRecord>({ url: `/devices/${deviceId}/commands`, method: 'post', data: body }),

  listCommands: (deviceId: string, limit = 20) =>
    request<CommandRecord[]>({
      url: `/devices/${deviceId}/commands`,
      method: 'get',
      params: { limit }
    }),

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
    }),

  mqttCredentials: () =>
    request<MqttCredential>({ url: '/mqtt/credentials', method: 'get' }),

  listAlarmRules: (params: { current?: number; size?: number; productId?: string | null } = {}) =>
    request<PageResult<AlarmRule>>({
      url: '/alarm-rules',
      method: 'get',
      params: { current: 1, size: 100, ...params }
    }),

  createAlarmRule: (body: {
    productId: string
    name: string
    condition: ConditionNode
    durationSeconds: number
    level: string
  }) => request<AlarmRule>({ url: '/alarm-rules', method: 'post', data: body }),

  updateAlarmRule: (id: string, body: { enabled?: boolean; threshold?: number; durationSeconds?: number; level?: string; name?: string }) =>
    request<AlarmRule>({ url: `/alarm-rules/${id}`, method: 'put', data: body }),

  deleteAlarmRule: (id: string) =>
    request<void>({ url: `/alarm-rules/${id}`, method: 'delete' }),

  listAlarms: (params: AlarmQuery) =>
    request<PageResult<AlarmRecord>>({ url: '/alarms', method: 'get', params }),

  ackAlarm: (id: string) =>
    request<void>({ url: `/alarms/${id}/ack`, method: 'post' }),

  closeAlarm: (id: string) =>
    request<void>({ url: `/alarms/${id}/close`, method: 'post' })
}
