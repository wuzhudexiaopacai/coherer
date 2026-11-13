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
  status?: string
  keyword?: string
}
