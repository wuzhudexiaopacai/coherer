<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { hexToRgba } from '../utils/format'

const props = withDefaults(
  defineProps<{
    points: { label: string; value: number }[]
    height?: string
    color?: string
  }>(),
  { height: '260px', color: '#0071e3' }
)

const el = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

function render() {
  if (!chart) return
  const color = props.color
  chart.setOption(
    {
      animationDuration: 500,
      grid: { left: 6, right: 22, top: 16, bottom: 4, containLabel: true },
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(17, 24, 39, 0.92)',
        borderWidth: 0,
        padding: [8, 12],
        textStyle: { color: '#fff', fontSize: 12 },
        axisPointer: { type: 'line', lineStyle: { color: 'rgba(0,113,227,.28)' } }
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: props.points.map((point) => point.label),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#8a8a8f', fontSize: 11, margin: 12, hideOverlap: true }
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: 'rgba(0,0,0,.05)', type: 'dashed' } },
        axisLabel: { color: '#8a8a8f', fontSize: 11 }
      },
      series: [
        {
          type: 'line',
          smooth: 0.35,
          symbol: 'none',
          data: props.points.map((point) => point.value),
          lineStyle: { width: 2.4, color },
          itemStyle: { color },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: hexToRgba(color, 0.22) },
              { offset: 1, color: hexToRgba(color, 0) }
            ])
          }
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

watch(() => props.points, render, { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="chart-wrap" :style="{ height }">
    <div ref="el" class="chart" />
    <div v-if="points.length === 0" class="chart-empty">
      <p>暂无数据</p>
      <span>设备上报后，趋势将在此呈现</span>
    </div>
  </div>
</template>

<style scoped>
.chart-wrap {
  position: relative;
  width: 100%;
}

.chart {
  width: 100%;
  height: 100%;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--text-3);
  font-size: 13px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.2), rgba(255, 255, 255, 0.55));
  border-radius: 12px;
}

.chart-empty span {
  font-size: 11.5px;
  color: var(--text-3);
}
</style>
