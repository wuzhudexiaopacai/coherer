export interface PageResult<T> {
  total: number
  page: number
  size: number
  records: T[]
}

export interface Product {
  id: string
  productKey: string
  name: string
  category: string
  nodeType: string
  authType: string
  status: string
  description?: string
  createdAt: string
}

export type DeviceStatus = 'INACTIVE' | 'ONLINE' | 'OFFLINE' | 'DISABLED'

export interface Device {
  id: string
  productId: string
  spaceId?: string
  deviceId: string
  name: string
  status: DeviceStatus
  lastOnlineAt?: string
  createdAt: string
}

export interface RegisterResult {
  deviceId: string
  name: string
  deviceSecret: string
}

export interface Telemetry {
  deviceId: string
  ts: string
  params: Record<string, number | string | boolean>
  receivedAt: string
}

export interface Shadow {
  deviceId: string
  desired: Record<string, unknown>
  reported: Record<string, unknown>
  version: string
  updatedAt: string
}

export interface Overview {
  productTotal: number
  deviceTotal: number
  online: number
  offline: number
  inactive: number
  disabled: number
  telemetryTotal: number
  telemetryToday: number
}

export interface TrendPoint {
  time: string
  count: number
}

export interface ThingModel {
  id: string
  type: 'PROPERTY' | 'EVENT' | 'SERVICE'
  identifier: string
  name: string
  dataType?: string
  accessMode: string
  unit?: string
  spec: Record<string, unknown>
}

export interface DeviceQuery {
  current?: number
  size?: number
  productId?: string | null
  spaceId?: string | null
  status?: string
  keyword?: string
}

export interface MqttCredential {
  url: string
  clientId: string
  username: string
  password: string
  ttlSeconds: number
  spaceId?: string | null
}

export interface UserInfo {
  id: string
  username: string
  nickname: string
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER'
  spaceId?: string | null
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  user: UserInfo
}

export interface AlarmRule {
  id: string
  productId: string
  name: string
  propertyIdentifier: string
  operator: string
  threshold: number
  condition?: ConditionNode
  conditionText?: string
  durationSeconds: number
  level: string
  enabled: boolean
  createdAt: string
}

export interface ConditionNode {
  logic?: string
  items?: ConditionNode[]
  property?: string
  operator?: string
  threshold?: number
  recoverThreshold?: number
}

export interface AlarmRecord {
  id: string
  ruleId: string
  ruleName: string
  productId: string
  deviceId: string
  level: string
  status: string
  message: string
  value?: number
  triggeredAt: string
  ackedAt?: string
  recoveredAt?: string
  closedAt?: string
}

export interface AlarmQuery {
  current?: number
  size?: number
  status?: string
  level?: string
  deviceId?: string
  spaceId?: string | null
}

export interface SpaceNode {
  id: string
  parentId?: string
  name: string
  code?: string
  type: string
  sort: number
  description?: string
  status: string
  createdAt?: string
  children: SpaceNode[]
}

export interface UserItem {
  id: string
  username: string
  nickname: string
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER'
  spaceId?: string | null
  status: 'ACTIVE' | 'DISABLED'
  createdAt?: string
}

export interface CommandRecord {
  id: string
  deviceId: string
  identifier: string
  requestId: string
  params: Record<string, unknown>
  status: 'PENDING' | 'SENT' | 'SUCCESS' | 'FAILED' | 'TIMEOUT'
  result?: Record<string, unknown>
  error?: string
  operator?: string
  createdAt: string
  sentAt?: string
  repliedAt?: string
}

export interface AuditLogItem {
  id: string
  username: string
  action: string
  resource?: string
  detail?: Record<string, unknown>
  ip?: string
  traceId?: string
  result?: 'SUCCESS' | 'FAILURE'
  userAgent?: string
  createdAt: string
}

export interface DeviceEvent {
  id: string
  deviceId: string
  identifier: string
  eventId?: string
  ts: string
  params: Record<string, unknown>
  createdAt: string
}
