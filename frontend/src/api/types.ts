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

export interface Project {
  id: string
  code: string
  name: string
  status: 'ACTIVE' | 'DISABLED'
  description?: string
  spaceCount: number
  hostCount: number
  deviceCount: number
  createdAt: string
  updatedAt?: string
}

export interface Device {
  id: string
  productId: string
  projectId?: string
  spaceId?: string
  hostId?: string | null
  deviceId: string
  name: string
  status: DeviceStatus
  firmwareVersion?: string
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
  projectId?: string | null
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
  tenantId?: string
  spaceId?: string | null
}

export interface UserInfo {
  id: string
  tenantId?: string
  tenantName?: string
  platformAdmin?: boolean
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
  projectId?: string | null
  spaceId?: string | null
}

export interface SpaceNode {
  id: string
  projectId?: string
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
  tenantId?: string
  platformAdmin?: boolean
  username: string
  nickname: string
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER'
  spaceId?: string | null
  status: 'ACTIVE' | 'DISABLED'
  createdAt?: string
}

export interface Tenant {
  id: string
  name: string
  status: 'ACTIVE' | 'DISABLED'
  plan: string
  description?: string
  userCount: number
  projectCount: number
  deviceCount: number
  createdAt: string
  updatedAt?: string
}

export interface TenantCreateBody {
  code: string
  name: string
  plan?: string
  description?: string
  adminUsername: string
  adminPassword: string
  adminNickname?: string
}

export interface TenantUpdateBody {
  name?: string
  status?: 'ACTIVE' | 'DISABLED'
  plan?: string
  description?: string
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

export interface Host {
  id: string
  hostId: string
  name: string
  projectId?: string
  spaceId: string
  model?: string
  firmwareVersion?: string
  status: 'INACTIVE' | 'ONLINE' | 'OFFLINE' | 'DISABLED'
  lastOnlineAt?: string
  createdAt: string
  childCount: number
}

export interface HostRegisterResult {
  hostId: string
  name: string
  hostSecret: string
}

export interface Firmware {
  id: string
  productId: string
  version: string
  fileName: string
  /** 后端 Long 统一序列化为字符串 */
  sizeBytes: number | string
  sha256: string
  storageType?: 'LOCAL' | 'S3'
  createdBy?: string
  createdAt: string
}

export type OtaStatus = 'PENDING' | 'SENT' | 'DOWNLOADING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT'

export interface OtaTask {
  id: string
  firmwareId: string
  firmwareVersion?: string
  deviceId: string
  status: OtaStatus
  progress: number
  error?: string
  attempt?: number
  maxAttempts?: number
  nextAttemptAt?: string
  fromVersion?: string
  taskType?: 'NORMAL' | 'ROLLBACK'
  rollbackOfTaskId?: string
  rolloutId?: string
  batchNo?: number
  createdBy?: string
  createdAt: string
  sentAt?: string
  finishedAt?: string
}

export type OtaRolloutStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'PAUSED'
  | 'SUCCESS'
  | 'PARTIAL_FAILED'
  | 'FAILED'
  | 'CANCELED'

export type OtaRolloutDeviceStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED'

export interface OtaRollout {
  id: string
  firmwareId: string
  firmwareVersion?: string
  productId: string
  projectId?: string
  name: string
  targetType: 'DEVICE' | 'SPACE' | 'ALL'
  targetSpaceId?: string
  status: OtaRolloutStatus
  batchSize: number
  failureThresholdPercent: number
  maxAttempts: number
  autoAdvance: boolean
  pauseReason?: string
  currentBatch: number
  totalBatches: number
  totalCount: number
  pendingCount: number
  runningCount: number
  successCount: number
  failedCount: number
  skippedCount: number
  createdBy?: string
  createdAt: string
  startedAt?: string
  finishedAt?: string
}

export interface OtaRolloutDevice {
  deviceId: string
  deviceName?: string
  batchNo: number
  status: OtaRolloutDeviceStatus
  taskId?: string
  taskStatus?: OtaStatus
  progress?: number
  error?: string
}

export interface OtaRolloutDetail extends OtaRollout {
  devices: OtaRolloutDevice[]
}

export interface OtaRolloutCreateBody {
  firmwareId: string
  projectId?: string
  name: string
  targetType: 'DEVICE' | 'SPACE' | 'ALL'
  spaceId?: string
  deviceIds?: string[]
  batchSize: number
  failureThresholdPercent: number
  maxAttempts?: number
  autoAdvance: boolean
}
