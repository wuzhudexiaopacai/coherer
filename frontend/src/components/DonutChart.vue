<script setup lang="ts">
import * as echarts from 'echarts'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    data: { name: string; value: number; color: string }[]
    height?: string
    centerLabel?: string
  }>(),
  { height: '240px', centerLabel: '设备' }
)

const el = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

const total = computed(() => props.data.reduce((sum, item) => sum + item.value, 0))

function render() {
  if (!chart) return
  chart.setOption(
    {
      animationDuration: 500,
      tooltip: {
        trigger: 'item',
        backgroundColor: 'rgba(17, 24, 39, 0.92)',
        borderWidth: 0,
        padding: [8, 12],
        textStyle: { color: '#fff', fontSize: 12 }
      },
      series: [
        {
          type: 'pie',
          radius: ['64%', '86%'],
          avoidLabelOverlap: true,
          itemStyle: {
            borderColor: 'rgba(255,255,255,.95)',
            borderWidth: 3,
            borderRadius: 6
          },
          label: { show: false },
          emphasis: { scaleSize: 3 },
          data: props.data.map((item) => ({
            value: item.value,
            name: item.name,
            itemStyle: { color: item.color }
          }))
        }
      ]
    },
    true
  )
}

onMounted(() => {
  if (!el.value) return
  chart = echarts.init(el.value)
  render()
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(el.value)
})

watch(() => props.data, render, { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="donut" :style="{ height }">
    <div ref="el" class="chart" />
    <div class="center">
      <strong>{{ total }}</strong>
      <span>{{ centerLabel }}</span>
    </div>
  </div>
</template>

<style scoped>
.donut {
  position: relative;
  width: 100%;
}

.chart {
  width: 100%;
  height: 100%;
}

.center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
  gap: 2px;
}

.center strong {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
}

.center span {
  font-size: 11px;
  color: var(--text-3);
  letter-spacing: 0.06em;
}
</style>
