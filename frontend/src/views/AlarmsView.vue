<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import type { AlarmRecord, AlarmRule } from '../api/types'
import { useProductStore } from '../stores/products'
import { useSpaceStore } from '../stores/spaces'
import { fmtDateTime, fmtRelative } from '../utils/format'

const productStore = useProductStore()
const spaceStore = useSpaceStore()
const view = ref<'records' | 'rules'>('records')
let timer: number | undefined

const statusOptions = [
  { label: '全部', value: '' },
  { label: '触发', value: 'TRIGGERED' },
  { label: '已确认', value: 'ACKNOWLEDGED' },
  { label: '已恢复', value: 'RECOVERED' },
  { label: '已关闭', value: 'CLOSED' }
]

const levelOptions = [
  { label: '全部级别', value: '' },
  { label: '提示', value: 'INFO' },
  { label: '警告', value: 'WARNING' },
  { label: '严重', value: 'CRITICAL' }
]

const recordFilters = reactive({ status: '', level: '' })
const recordState = reactive({
  current: 1,
  size: 10,
  total: 0,
  records: [] as AlarmRecord[],
  loading: false
})

async function loadRecords() {
  recordState.loading = true
  try {
    const page = await api.listAlarms({
      current: recordState.current,
      size: recordState.size,
      status: recordFilters.status || undefined,
      level: recordFilters.level || undefined,
      spaceId: spaceStore.currentSpaceId ?? undefined
    })
    recordState.records = page.records
    recordState.total = page.total
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    recordState.loading = false
  }
}

function applyRecordFilters() {
  recordState.current = 1
  loadRecords()
}

async function acknowledge(row: AlarmRecord) {
  try {
    await api.ackAlarm(row.id)
    ElMessage.success('告警已确认')
    await loadRecords()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

async function closeAlarm(row: AlarmRecord) {
  try {
    await api.closeAlarm(row.id)
    ElMessage.success('告警已关闭')
    await loadRecords()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

const ruleFilter = reactive({ productId: null as string | null })
const ruleState = reactive({
  current: 1,
  size: 20,
  total: 0,
  records: [] as AlarmRule[],
  loading: false
})

async function loadRules() {
  ruleState.loading = true
  try {
    const page = await api.listAlarmRules({
      current: ruleState.current,
      size: ruleState.size,
      productId: ruleFilter.productId ?? undefined
    })
    ruleState.records = page.records
    ruleState.total = page.total
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    ruleState.loading = false
  }
}

const createDialog = reactive({
  visible: false,
  loading: false,
  productId: null as string | null,
  name: '',
  propertyIdentifier: 'temperature',
  operator: 'GT',
  threshold: 30,
  durationSeconds: 0,
  level: 'WARNING'
})

function openCreate() {
  createDialog.productId = productStore.products[0]?.id ?? null
  createDialog.name = ''
  createDialog.propertyIdentifier = 'temperature'
  createDialog.operator = 'GT'
  createDialog.threshold = 30
  createDialog.durationSeconds = 0
  createDialog.level = 'WARNING'
  createDialog.visible = true
}

async function submitRule() {
  if (!createDialog.productId) {
    ElMessage.warning('请选择产品')
    return
  }
  if (!createDialog.name.trim()) {
    ElMessage.warning('请输入规则名称')
    return
  }
  createDialog.loading = true
  try {
    await api.createAlarmRule({
      productId: createDialog.productId,
      name: createDialog.name.trim(),
      propertyIdentifier: createDialog.propertyIdentifier.trim(),
      operator: createDialog.operator,
      threshold: createDialog.threshold,
      durationSeconds: createDialog.durationSeconds,
      level: createDialog.level
    })
    ElMessage.success('告警规则已创建')
    createDialog.visible = false
    await loadRules()
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    createDialog.loading = false
  }
}

async function toggleRule(rule: AlarmRule, enabled: boolean) {
  try {
    await api.updateAlarmRule(rule.id, { enabled })
    ElMessage.success(enabled ? '规则已启用' : '规则已停用')
  } catch (error) {
    rule.enabled = !enabled
    ElMessage.error((error as Error).message)
  }
}

async function removeRule(rule: AlarmRule) {
  try {
    await ElMessageBox.confirm(`确定删除规则「${rule.name}」吗？`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await api.deleteAlarmRule(rule.id)
    ElMessage.success('规则已删除')
    await loadRules()
  } catch (error) {
    ElMessage.error((error as Error).message)
  }
}

const levelMeta = (level: string) => {
  if (level === 'CRITICAL') return { label: '严重', cls: 'critical' }
  if (level === 'INFO') return { label: '提示', cls: 'info' }
  return { label: '警告', cls: 'warning' }
}

const statusMeta = (status: string) => {
  switch (status) {
    case 'TRIGGERED':
      return { label: '触发', cls: 'triggered' }
    case 'ACKNOWLEDGED':
      return { label: '已确认', cls: 'acked' }
    case 'RECOVERED':
      return { label: '已恢复', cls: 'recovered' }
    case 'CLOSED':
      return { label: '已关闭', cls: 'closed' }
    default:
      return { label: status, cls: 'closed' }
  }
}

const operatorSymbol = (operator: string) =>
  ({ GT: '>', GE: '≥', LT: '<', LE: '≤', EQ: '=' })[operator] ?? operator

const productName = (productId?: string) => productStore.nameOf(productId ?? '')

onMounted(async () => {
  await Promise.all([productStore.ensureLoaded(), spaceStore.ensureLoaded()])
  await Promise.all([loadRecords(), loadRules()])
  timer = window.setInterval(() => {
    if (view.value === 'records') loadRecords()
  }, 10_000)
})

watch(
  () => spaceStore.currentSpaceId,
  () => {
    recordState.current = 1
    if (view.value === 'records') {
      void loadRecords()
    }
  }
)

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="alarms">
    <div class="page-head">
      <div>
        <h2 class="page-title">告警中心</h2>
        <p class="page-sub">规则触发的告警记录、确认与关闭，以及阈值规则配置</p>
      </div>
      <div class="head-actions">
        <div class="segmented">
          <button :class="{ active: view === 'records' }" @click="view = 'records'">告警记录</button>
          <button :class="{ active: view === 'rules' }" @click="view = 'rules'">规则配置</button>
        </div>
        <el-button v-if="view === 'records'" :icon="Refresh" @click="loadRecords">刷新</el-button>
        <el-button v-else type="primary" :icon="Plus" @click="openCreate">新建规则</el-button>
      </div>
    </div>

    <template v-if="view === 'records'">
      <section class="card toolbar">
        <div class="segmented">
          <button
            v-for="option in statusOptions"
            :key="option.value"
            :class="{ active: recordFilters.status === option.value }"
            @click="((recordFilters.status = option.value), applyRecordFilters())"
          >
            {{ option.label }}
          </button>
        </div>
        <el-select
          v-model="recordFilters.level"
          class="level-select"
          placeholder="全部级别"
          clearable
          @change="applyRecordFilters"
        >
          <el-option
            v-for="option in levelOptions.slice(1)"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
        <span class="auto-refresh">每 10 秒自动刷新 · MQTT 遥测实时评估</span>
      </section>

      <section class="card table-card">
        <el-table v-loading="recordState.loading" :data="recordState.records" size="large">
          <el-table-column label="级别" width="88">
            <template #default="{ row }">
              <span class="level-badge" :class="levelMeta(row.level).cls">
                {{ levelMeta(row.level).label }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="告警内容" min-width="300">
            <template #default="{ row }">
              <div class="alarm-content">
                <span>{{ row.message }}</span>
                <span class="tiny">{{ row.ruleName }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="设备" min-width="170">
            <template #default="{ row }">
              <span class="mono">{{ row.deviceId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span class="status-badge" :class="statusMeta(row.status).cls">
                {{ statusMeta(row.status).label }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="触发时间" width="170">
            <template #default="{ row }">
              <span :title="fmtDateTime(row.triggeredAt)">{{ fmtRelative(row.triggeredAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="156" align="right">
            <template #default="{ row }">
              <div class="row-actions">
                <el-button
                  v-if="row.status === 'TRIGGERED'"
                  text
                  type="primary"
                  size="small"
                  @click="acknowledge(row)"
                >
                  确认
                </el-button>
                <el-button
                  v-if="row.status === 'TRIGGERED' || row.status === 'ACKNOWLEDGED'"
                  text
                  size="small"
                  @click="closeAlarm(row)"
                >
                  关闭
                </el-button>
                <span
                  v-if="row.status === 'RECOVERED' || row.status === 'CLOSED'"
                  class="tiny"
                >
                  —
                </span>
              </div>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-state">
              <p>暂无告警记录</p>
              <span class="tiny">创建阈值规则后，遥测触发条件时会在这里生成告警</span>
            </div>
          </template>
        </el-table>

        <el-pagination
          v-model:current-page="recordState.current"
          v-model:page-size="recordState.size"
          :total="recordState.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadRecords"
          @size-change="((recordState.current = 1), loadRecords())"
        />
      </section>
    </template>

    <template v-else>
      <section class="card toolbar">
        <el-select
          v-model="ruleFilter.productId"
          class="product-select"
          placeholder="全部产品"
          clearable
          @change="((ruleState.current = 1), loadRules())"
        >
          <el-option
            v-for="product in productStore.products"
            :key="product.id"
            :label="product.name"
            :value="product.id"
          />
        </el-select>
        <span class="auto-refresh">规则变更后 60 秒缓存自动失效，立即生效</span>
      </section>

      <section class="card table-card">
        <el-table v-loading="ruleState.loading" :data="ruleState.records" size="large">
          <el-table-column prop="name" label="规则名称" min-width="160" />
          <el-table-column label="产品" min-width="140">
            <template #default="{ row }">{{ productName(row.productId) }}</template>
          </el-table-column>
          <el-table-column label="触发条件" min-width="200">
            <template #default="{ row }">
              <span class="mono">
                {{ row.propertyIdentifier }} {{ operatorSymbol(row.operator) }} {{ row.threshold }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="持续" width="90">
            <template #default="{ row }">
              <span class="tiny">{{ row.durationSeconds > 0 ? `${row.durationSeconds}s` : '立即' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="级别" width="88">
            <template #default="{ row }">
              <span class="level-badge" :class="levelMeta(row.level).cls">
                {{ levelMeta(row.level).label }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="80">
            <template #default="{ row }">
              <el-switch
                v-model="row.enabled"
                @change="(value: string | number | boolean) => toggleRule(row, Boolean(value))"
              />
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">
              <span class="tiny">{{ fmtDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="right">
            <template #default="{ row }">
              <el-button text type="danger" @click="removeRule(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-state">
              <p>暂无告警规则</p>
              <span class="tiny">点击「新建规则」配置属性阈值与持续时长</span>
            </div>
          </template>
        </el-table>

        <el-pagination
          v-model:current-page="ruleState.current"
          v-model:page-size="ruleState.size"
          :total="ruleState.total"
          :page-sizes="[20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadRules"
          @size-change="((ruleState.current = 1), loadRules())"
        />
      </section>
    </template>

    <el-dialog v-model="createDialog.visible" title="新建告警规则" width="520px">
      <div class="form-grid">
        <label class="field">
          <span class="field-label">所属产品</span>
          <el-select v-model="createDialog.productId" placeholder="选择产品">
            <el-option
              v-for="product in productStore.products"
              :key="product.id"
              :label="`${product.name} (${product.productKey})`"
              :value="product.id"
            />
          </el-select>
        </label>
        <label class="field">
          <span class="field-label">规则名称</span>
          <el-input v-model="createDialog.name" placeholder="例如：高温告警" maxlength="64" />
        </label>
        <div class="form-row">
          <label class="field">
            <span class="field-label">属性标识</span>
            <el-input v-model="createDialog.propertyIdentifier" placeholder="temperature" />
          </label>
          <label class="field narrow">
            <span class="field-label">比较符</span>
            <el-select v-model="createDialog.operator">
              <el-option label="> 大于" value="GT" />
              <el-option label="≥ 大于等于" value="GE" />
              <el-option label="< 小于" value="LT" />
              <el-option label="≤ 小于等于" value="LE" />
              <el-option label="= 等于" value="EQ" />
            </el-select>
          </label>
          <label class="field narrow">
            <span class="field-label">阈值</span>
            <el-input-number v-model="createDialog.threshold" :controls="false" />
          </label>
        </div>
        <div class="form-row">
          <label class="field">
            <span class="field-label">持续时长（秒）</span>
            <el-input-number v-model="createDialog.durationSeconds" :min="0" :max="86400" />
          </label>
          <label class="field">
            <span class="field-label">级别</span>
            <el-select v-model="createDialog.level">
              <el-option label="提示" value="INFO" />
              <el-option label="警告" value="WARNING" />
              <el-option label="严重" value="CRITICAL" />
            </el-select>
          </label>
        </div>
      </div>
      <p class="form-hint">
        规则立即生效：遥测校验通过后按物模型属性值评估；持续 0 秒表示条件成立即触发。
      </p>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.loading" @click="submitRule">
          创建规则
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.level-select {
  width: 150px;
}

.product-select {
  width: 200px;
}

.auto-refresh {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--text-3);
}

.table-card {
  padding: 8px 20px 16px;
}

.level-badge {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 500;
  white-space: nowrap;
}

.level-badge.info {
  background: rgba(0, 113, 227, 0.1);
  color: var(--accent);
}
.level-badge.warning {
  background: rgba(232, 134, 12, 0.12);
  color: var(--warning);
}
.level-badge.critical {
  background: rgba(255, 69, 58, 0.12);
  color: var(--danger);
}

.status-badge {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 500;
  white-space: nowrap;
}

.status-badge.triggered {
  background: rgba(255, 69, 58, 0.12);
  color: var(--danger);
}
.status-badge.acked {
  background: rgba(232, 134, 12, 0.12);
  color: var(--warning);
}
.status-badge.recovered {
  background: rgba(47, 180, 87, 0.12);
  color: var(--success);
}
.status-badge.closed {
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-3);
}

.alarm-content {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  white-space: nowrap;
  min-height: 32px;
}

.row-actions :deep(.el-button) {
  margin-left: 0;
  padding: 4px 6px;
}

.form-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-row {
  display: flex;
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
  flex: 1;
}

.field.narrow {
  flex: 0 0 130px;
}

.field-label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-2);
}

.field :deep(.el-select),
.field :deep(.el-input-number) {
  width: 100%;
}

.form-hint {
  margin-top: 14px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.7;
}

@media (max-width: 900px) {
  .head-actions {
    width: 100%;
    justify-content: space-between;
  }
  .auto-refresh {
    margin-left: 0;
  }
  .form-row {
    flex-direction: column;
  }
  .field.narrow {
    flex: 1;
  }
}
</style>
