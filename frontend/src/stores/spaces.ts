import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '../api'
import type { SpaceNode } from '../api/types'
import { useProjectStore } from './projects'

export interface FlatSpace extends SpaceNode {
  depth: number
}

const STORAGE_KEY = 'coherer.spaceId'

export const useSpaceStore = defineStore('spaces', () => {
  const projectStore = useProjectStore()
  const tree = ref<SpaceNode[]>([])
  const loading = ref(false)
  const currentSpaceId = ref<string | null>(localStorage.getItem(STORAGE_KEY))
  let loaded = false

  const flat = computed<FlatSpace[]>(() => {
    const result: FlatSpace[] = []
    const walk = (nodes: SpaceNode[], depth: number) => {
      for (const node of nodes) {
        result.push({ ...node, depth })
        if (node.children?.length) {
          walk(node.children, depth + 1)
        }
      }
    }
    walk(tree.value, 0)
    return result
  })

  async function ensureLoaded(force = false) {
    if (loaded && !force) return
    loading.value = true
    try {
      tree.value = await api.spaceTree(projectStore.currentProjectId)
      loaded = true
      // 数据范围可能变化：当前选中空间已不可见时回退到「全部可见空间」
      if (currentSpaceId.value && !flat.value.some((item) => item.id === currentSpaceId.value)) {
        setCurrent(null)
      }
    } finally {
      loading.value = false
    }
  }

  function setCurrent(id: string | null) {
    currentSpaceId.value = id
    if (id) {
      localStorage.setItem(STORAGE_KEY, id)
    } else {
      localStorage.removeItem(STORAGE_KEY)
    }
  }

  function nameOf(id?: string | null): string {
    if (!id) return '全部空间'
    return flat.value.find((item) => item.id === id)?.name ?? '—'
  }

  /** 切换项目时清空树与选中空间，等待 ensureLoaded(true) 重新拉取。 */
  function reset() {
    tree.value = []
    setCurrent(null)
    loaded = false
  }

  return { tree, flat, loading, currentSpaceId, ensureLoaded, setCurrent, nameOf, reset }
})
