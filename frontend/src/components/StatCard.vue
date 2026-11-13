<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    value: string
    note?: string
    tone?: 'blue' | 'green' | 'violet' | 'orange'
  }>(),
  { tone: 'blue', note: '' }
)
</script>

<template>
  <div class="stat-card card" :class="tone">
    <div class="stat-head">
      <span class="stat-label">{{ label }}</span>
      <span class="stat-icon"><slot name="icon" /></span>
    </div>
    <p class="kpi-value">{{ value }}</p>
    <p v-if="note" class="stat-note">{{ note }}</p>
  </div>
</template>

<style scoped>
.stat-card {
  padding: 20px 22px 18px;
  transition: transform var(--transition), box-shadow var(--transition);
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-lift);
}

.stat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.stat-label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text-2);
  letter-spacing: 0.01em;
}

.stat-icon {
  width: 36px;
  height: 36px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  font-size: 17px;
}

.blue .stat-icon {
  background: rgba(0, 113, 227, 0.1);
  color: var(--accent);
}
.green .stat-icon {
  background: rgba(47, 180, 87, 0.12);
  color: var(--success);
}
.violet .stat-icon {
  background: rgba(124, 108, 246, 0.12);
  color: var(--violet);
}
.orange .stat-icon {
  background: rgba(240, 138, 60, 0.13);
  color: var(--orange);
}

.stat-note {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-3);
}
</style>
