<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { hexToRgba } from '../utils/format'

interface Series {
  name: string
  color: string
  axis?: 'left' | 'right'
  points: { label: string; value: number }[]
}

const props = withDefaults(
  defineProps<{ series: Series[]; height?: string }>(),
  { height: '300px' }
)

const el = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

function render() {
  if (!chart || props.series.length === 0) return
  const labels = props.series[0].points.map((point) => point.label)
  const hasRight = props.series.some((item) => item.axis === 'right')

  chart.setOption(
    {
      animationDuration: 400,
      grid: { left: 6, right: hasRight ? 10 : 12, top: 22, bottom: 4, containLabel: true },
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(17, 24, 39, 0.92)',
        borderWidth: 0,
        padding: [8, 12],
        textStyle: { color: '#fff', fontSize: 12 },
        axisPointer: { type: 'line', lineStyle: { color: 'rgba(0,0,0,.18)' } }
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: labels,
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#8a8a8f', fontSize: 11, margin: 12, hideOverlap: true }
      },
      yAxis: [
        {
          type: 'value',
          position: 'left',
          scale: true,
          splitLine: { lineStyle: { color: 'rgba(0,0,0,.05)', type: 'dashed' } },
          axisLabel: { color: '#8a8a8f', fontSize: 11 }
        },
        {
          type: 'value',
          position: 'right',
          scale: true,
          show: hasRight,
          splitLine: { show: false },
          axisLabel: { color: '#8a8a8f', fontSize: 11 }
        }
      ],
      series: props.series.map((item) => ({
        name: item.name,
        type: 'line',
        yAxisIndex: item.axis === 'right' ? 1 : 0,
        smooth: 0.3,
        symbol: item.points.length <= 2 ? 'circle' : 'none',
        symbolSize: 6,
        showSymbol: item.points.length <= 2,
        data: item.points.map((point) => point.value),
        lineStyle: { width: 2.2, color: item.color },
        itemStyle: { color: item.color },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: hexToRgba(item.color, 0.16) },
            { offset: 1, color: hexToRgba(item.color, 0) }
          ])
        }
      }))
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

watch(() => props.series, render, { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="series-wrap" :style="{ height }">
    <div ref="el" class="chart" />
    <div class="legend">
      <span v-for="item in series" :key="item.name" class="legend-item">
        <span class="dot" :style="{ background: item.color }" />{{ item.name }}
      </span>
    </div>
    <div v-if="series.length === 0 || series[0].points.length === 0" class="chart-empty">
      <p>暂无遥测数据</p>
      <span>设备上报后自动刷新</span>
    </div>
  </div>
</template>

<style scoped>
.series-wrap {
  position: relative;
  width: 100%;
}

.chart {
  width: 100%;
  height: 100%;
}

.legend {
  position: absolute;
  top: 0;
  right: 4px;
  display: flex;
  gap: 14px;
  font-size: 11.5px;
  color: var(--text-2);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
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
}
</style>
