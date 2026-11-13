import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '../api'
import type { SpaceNode } from '../api/types'

export interface FlatSpace extends SpaceNode {
  depth: number
}

const STORAGE_KEY = 'coherer.spaceId'

export const useSpaceStore = defineStore('spaces', () => {
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
      tree.value = await api.spaceTree()
      loaded = true
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

  return { tree, flat, loading, currentSpaceId, ensureLoaded, setCurrent, nameOf }
})
