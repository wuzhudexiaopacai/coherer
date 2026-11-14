<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import type { AuditLogItem } from '../api/types'
import { fmtDateTime } from '../utils/format'

const state = reactive({
  current: 1,
  size: 20,
  total: 0,
  records: [] as AuditLogItem[],
  loading: false
})

const filters = reactive({
  username: '',
  action: '',
  result: ''
})

const resultOptions = [
  { label: '全部结果', value: '' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILURE' }
]

async function load() {
  state.loading = true
  try {
    const page = await api.listAuditLogs({
      current: state.current,
      size: state.size,
      username: filters.username || undefined,
      action: filters.action || undefined,
      result: filters.result || undefined
    })
    state.records = page.records
    state.total = page.total
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    state.loading = false
  }
}

function search() {
  state.current = 1
  void load()
}

function detailText(row: AuditLogItem): string {
  if (!row.detail || Object.keys(row.detail).length === 0) return '—'
  return JSON.stringify(row.detail)
}

onMounted(load)
</script>

<template>
  <div class="audit">
    <div class="page-head">
      <div>
        <h2 class="page-title">审计日志</h2>
        <p class="page-sub">登录、设备、告警、规则、用户与命令等关键操作；含 traceId 与执行结果</p>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <section class="card">
      <div class="toolbar">
        <el-input
          v-model="filters.username"
          class="field-input"
          placeholder="用户名"
          clearable
          @keyup.enter="search"
        />
        <el-input
          v-model="filters.action"
          class="field-input"
          placeholder="动作，如 LOGIN_FAILURE"
          clearable
          @keyup.enter="search"
        />
        <el-select v-model="filters.result" class="field-select">
          <el-option
            v-for="option in resultOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
      </div>

      <el-table v-loading="state.loading" :data="state.records" size="large">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <span class="tiny">{{ fmtDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="用户" width="100" prop="username" />
        <el-table-column label="动作" width="160">
          <template #default="{ row }">
            <span class="mono action">{{ row.action }}</span>
          </template>
        </el-table-column>
        <el-table-column label="资源" width="90" prop="resource" />
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <span class="chip" :class="{ accent: row.result === 'SUCCESS', danger: row.result === 'FAILURE' }">
              {{ row.result === 'FAILURE' ? '失败' : '成功' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="IP" width="120">
          <template #default="{ row }">
            <span class="mono tiny">{{ row.ip || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="详情" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono tiny detail" :title="detailText(row)">{{ detailText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="traceId" width="130">
          <template #default="{ row }">
            <el-tooltip v-if="row.traceId" :content="row.traceId" placement="top">
              <span class="mono tiny">{{ row.traceId.slice(0, 12) }}…</span>
            </el-tooltip>
            <span v-else class="tiny">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state">
            <p>暂无审计记录</p>
            <span class="tiny">登录或执行写操作后会在这里留痕</span>
          </div>
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="state.current"
        v-model:page-size="state.size"
        :total="state.total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="load"
        @size-change="((state.current = 1), load())"
      />
    </section>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  padding: 12px 16px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.field-input {
  width: 210px;
}

.field-select {
  width: 130px;
}

.action {
  font-size: 11.5px;
  color: var(--text-2);
}

.detail {
  color: var(--text-3);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.audit :deep(.el-table__cell) {
  vertical-align: middle;
}

.chip.danger {
  background: rgba(255, 69, 58, 0.1);
  color: #d70015;
}
</style>
