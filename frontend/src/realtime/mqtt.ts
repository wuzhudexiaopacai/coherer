import mqtt, { type MqttClient } from 'mqtt'
import { ref } from 'vue'
import { api } from '../api'

/** 全局 MQTT 实时连接状态（顶栏实时标签使用）。 */
export const mqttConnected = ref(false)
export const mqttError = ref('')

type Handler = (topic: string, payload: string) => void

const handlers = new Map<string, Set<Handler>>()
let client: MqttClient | null = null
let connecting = false
let retryTimer: number | undefined

/** 简化版 MQTT 主题过滤器匹配（支持 + 与 #）。 */
function matchFilter(filter: string, topic: string): boolean {
  const f = filter.split('/')
  const t = topic.split('/')
  for (let i = 0; i < f.length; i += 1) {
    if (f[i] === '#') return true
    if (i >= t.length) return false
    if (f[i] !== '+' && f[i] !== t[i]) return false
  }
  return f.length === t.length
}

function scheduleReconnect() {
  if (handlers.size === 0) return
  window.clearTimeout(retryTimer)
  retryTimer = window.setTimeout(() => {
    void ensureRealtime()
  }, 5000)
}

export async function ensureRealtime(): Promise<void> {
  if (connecting || client?.connected || handlers.size === 0) return
  connecting = true
  try {
    const credential = await api.mqttCredentials()
    const next = mqtt.connect(credential.url, {
      clientId: credential.clientId,
      username: credential.username,
      password: credential.password,
      clean: true,
      reconnectPeriod: 0,
      connectTimeout: 5000
    })
    client = next

    next.on('connect', () => {
      mqttConnected.value = true
      mqttError.value = ''
      for (const topic of handlers.keys()) {
        next.subscribe(topic, { qos: 1 })
      }
    })

    next.on('message', (topic, payload) => {
      const text = payload.toString()
      for (const [filter, set] of handlers) {
        if (matchFilter(filter, topic)) {
          set.forEach((handler) => handler(topic, text))
        }
      }
    })

    next.on('close', () => {
      if (client === next) {
        mqttConnected.value = false
        client = null
        next.end(true)
        scheduleReconnect()
      }
    })

    next.on('error', (error) => {
      mqttError.value = error.message
      mqttConnected.value = false
    })
  } catch (error) {
    mqttError.value = (error as Error).message
    mqttConnected.value = false
    scheduleReconnect()
  } finally {
    connecting = false
  }
}

/** 订阅实时主题，返回取消订阅函数。 */
export function subscribeRealtime(filter: string, handler: Handler): () => void {
  if (!handlers.has(filter)) {
    handlers.set(filter, new Set())
  }
  handlers.get(filter)!.add(handler)
  void ensureRealtime()
  if (client?.connected) {
    client.subscribe(filter, { qos: 1 })
  }
  return () => {
    const set = handlers.get(filter)
    set?.delete(handler)
    if (set && set.size === 0) {
      handlers.delete(filter)
      if (client?.connected) {
        client.unsubscribe(filter)
      }
    }
  }
}
