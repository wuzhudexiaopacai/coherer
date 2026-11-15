<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: string }>()

const meta = computed(() => {
  switch (props.status) {
    case 'ACTIVE':
      return { label: '启用中', color: 'var(--success)' }
    case 'ONLINE':
      return { label: '在线', color: 'var(--success)' }
    case 'OFFLINE':
      return { label: '离线', color: 'var(--text-3)' }
    case 'INACTIVE':
      return { label: '未激活', color: 'var(--warning)' }
    case 'DISABLED':
      return { label: '已禁用', color: 'var(--danger)' }
    case 'PENDING':
      return { label: '等待下发', color: 'var(--warning)' }
    case 'SENT':
      return { label: '已下发', color: 'var(--accent)' }
    case 'DOWNLOADING':
      return { label: '下载中', color: 'var(--accent)' }
    case 'RUNNING':
      return { label: '执行中', color: 'var(--accent)' }
    case 'PAUSED':
      return { label: '已暂停', color: 'var(--warning)' }
    case 'SUCCESS':
      return { label: '成功', color: 'var(--success)' }
    case 'PARTIAL_FAILED':
      return { label: '部分失败', color: 'var(--warning)' }
    case 'FAILED':
      return { label: '失败', color: 'var(--danger)' }
    case 'TIMEOUT':
      return { label: '超时', color: 'var(--warning)' }
    case 'CANCELED':
      return { label: '已取消', color: 'var(--text-3)' }
    case 'SKIPPED':
      return { label: '已跳过', color: 'var(--text-3)' }
    default:
      return { label: props.status || '未知', color: 'var(--text-3)' }
  }
})
</script>

<template>
  <span class="status-badge">
    <span class="dot" :style="{ background: meta.color }" />
    {{ meta.label }}
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 26px;
  padding: 0 11px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.035);
  color: var(--text-2);
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
}
</style>
