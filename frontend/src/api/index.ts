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
  Project,
  RegisterResult,
  Shadow,
  SpaceNode,
  Telemetry,
  Tenant,
  TenantCreateBody,
  TenantUpdateBody,
  ThingModel,
  TrendPoint,
  UserInfo,
  UserItem,
  CommandRecord,
  AuditLogItem,
  DeviceEvent,
  Firmware,
  Host,
  HostRegisterResult,
  OtaRollout,
  OtaRolloutCreateBody,
  OtaRolloutDetail,
  OtaTask
} from './types'

export const api = {
  login: (body: { tenantCode: string; username: string; password: string }) =>
    request<AuthResponse>({ url: '/auth/login', method: 'post', data: body }),

  refresh: (body: { refreshToken: string }) =>
    request<AuthResponse>({ url: '/auth/refresh', method: 'post', data: body }),

  me: () => request<UserInfo>({ url: '/auth/me', method: 'get' }),

  logout: () => request<void>({ url: '/auth/logout', method: 'post' }),

  listTenants: () => request<Tenant[]>({ url: '/tenants', method: 'get' }),

  createTenant: (body: TenantCreateBody) =>
    request<Tenant>({ url: '/tenants', method: 'post', data: body }),

  updateTenant: (id: string, body: TenantUpdateBody) =>
    request<Tenant>({ url: `/tenants/${id}`, method: 'put', data: body }),

  overview: (spaceId?: string | null, projectId?: string | null) =>
    request<Overview>({
      url: '/dashboard/overview',
      method: 'get',
      params: {
        ...(spaceId ? { spaceId } : {}),
        ...(projectId ? { projectId } : {})
      }
    }),

  telemetryTrend: (hours = 24, spaceId?: string | null, projectId?: string | null) =>
    request<TrendPoint[]>({
      url: '/dashboard/telemetry-trend',
      method: 'get',
      params: {
        hours,
        ...(spaceId ? { spaceId } : {}),
        ...(projectId ? { projectId } : {})
      }
    }),

  recentTelemetry: (limit = 10, spaceId?: string | null, projectId?: string | null) =>
    request<Telemetry[]>({
      url: '/dashboard/recent-telemetry',
      method: 'get',
      params: {
        limit,
        ...(spaceId ? { spaceId } : {}),
        ...(projectId ? { projectId } : {})
      }
    }),

  spaceTree: (projectId?: string | null) =>
    request<SpaceNode[]>({
      url: '/spaces/tree',
      method: 'get',
      params: projectId ? { projectId } : {}
    }),

  createSpace: (body: {
    projectId?: string
    parentId?: string
    name: string
    code?: string
    type?: string
    sort?: number
    description?: string
  }) => request<SpaceNode>({ url: '/spaces', method: 'post', data: body }),

  listProjects: () => request<Project[]>({ url: '/projects', method: 'get' }),

  getProject: (id: string) => request<Project>({ url: `/projects/${id}`, method: 'get' }),

  createProject: (body: {
    name: string
    code?: string
    description?: string
    status?: string
  }) => request<Project>({ url: '/projects', method: 'post', data: body }),

  updateProject: (id: string, body: {
    name?: string
    code?: string
    description?: string
    status?: string
  }) => request<Project>({ url: `/projects/${id}`, method: 'put', data: body }),

  deleteProject: (id: string) =>
    request<void>({ url: `/projects/${id}`, method: 'delete' }),

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

  listHosts: (params: {
    current?: number
    size?: number
    keyword?: string
    projectId?: string | null
    spaceId?: string | null
  } = {}) => request<PageResult<Host>>({ url: '/hosts', method: 'get', params }),

  registerHost: (body: {
    name: string
    spaceId?: string
    model?: string
    firmwareVersion?: string
  }) => request<HostRegisterResult>({ url: '/hosts', method: 'post', data: body }),

  hostDevices: (id: string) =>
    request<Device[]>({ url: `/hosts/${id}/devices`, method: 'get' }),

  bindHostDevice: (id: string, deviceId: string) =>
    request<void>({ url: `/hosts/${id}/devices/${deviceId}`, method: 'post' }),

  unbindHostDevice: (id: string, deviceId: string) =>
    request<void>({ url: `/hosts/${id}/devices/${deviceId}`, method: 'delete' }),

  enableHost: (id: string) => request<Host>({ url: `/hosts/${id}/enable`, method: 'post' }),

  disableHost: (id: string) => request<Host>({ url: `/hosts/${id}/disable`, method: 'post' }),

  rotateHostSecret: (id: string) =>
    request<HostRegisterResult>({ url: `/hosts/${id}/rotate-secret`, method: 'post' }),

  deleteHost: (id: string) => request<void>({ url: `/hosts/${id}`, method: 'delete' }),

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
    request<void>({ url: `/alarms/${id}/close`, method: 'post' }),

  listFirmwares: (productId: string) =>
    request<Firmware[]>({
      url: '/ota/firmwares',
      method: 'get',
      params: { productId }
    }),

  uploadFirmware: (productId: string, version: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<Firmware>({
      url: '/ota/firmwares',
      method: 'post',
      params: { productId, version },
      data: form,
      timeout: 120000
    })
  },

  deleteFirmware: (id: string) =>
    request<void>({ url: `/ota/firmwares/${id}`, method: 'delete' }),

  startOta: (deviceId: string, firmwareId: string, maxAttempts?: number) =>
    request<OtaTask>({
      url: '/ota/tasks',
      method: 'post',
      data: { deviceId, firmwareId, maxAttempts }
    }),

  rollbackOtaTask: (id: string) =>
    request<OtaTask>({ url: `/ota/tasks/${id}/rollback`, method: 'post' }),

  listOtaTasks: (deviceId: string, limit = 20) =>
    request<OtaTask[]>({
      url: '/ota/tasks',
      method: 'get',
      params: { deviceId, limit }
    }),

  createOtaRollout: (body: OtaRolloutCreateBody) =>
    request<OtaRolloutDetail>({
      url: '/ota/rollouts',
      method: 'post',
      data: body
    }),

  listOtaRollouts: (params: {
    current?: number
    size?: number
    productId?: string
    projectId?: string | null
    status?: string
  } = {}) =>
    request<PageResult<OtaRollout>>({
      url: '/ota/rollouts',
      method: 'get',
      params: { current: 1, size: 20, ...params }
    }),

  getOtaRollout: (id: string) =>
    request<OtaRolloutDetail>({ url: `/ota/rollouts/${id}`, method: 'get' }),

  pauseOtaRollout: (id: string, reason?: string) =>
    request<OtaRolloutDetail>({
      url: `/ota/rollouts/${id}/pause`,
      method: 'post',
      data: { reason }
    }),

  resumeOtaRollout: (id: string) =>
    request<OtaRolloutDetail>({ url: `/ota/rollouts/${id}/resume`, method: 'post' }),

  cancelOtaRollout: (id: string) =>
    request<OtaRolloutDetail>({ url: `/ota/rollouts/${id}/cancel`, method: 'post' })
}
