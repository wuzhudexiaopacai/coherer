package com.github.wuzhudexiaopacai.security;

import java.util.List;

/**
 * 当前用户的数据范围。
 *
 * @param unrestricted 是否可访问全部空间
 * @param rootSpaceId  受限用户的根空间（用于新建资源的默认归属）；全平台用户为 null
 * @param spaceIds     受限用户可访问的空间（含子空间）；unrestricted 时为空
 */
public record DataScope(boolean unrestricted, Long rootSpaceId, List<Long> spaceIds) {

    public static DataScope all() {
        return new DataScope(true, null, List.of());
    }

    public static DataScope of(Long rootSpaceId, List<Long> spaceIds) {
        return new DataScope(false, rootSpaceId, List.copyOf(spaceIds));
    }

    /** 是否允许访问指定空间的资源。 */
    public boolean allows(Long spaceId) {
        return unrestricted || (spaceId != null && spaceIds.contains(spaceId));
    }

    /** 受限且没有任何可见空间（用户被禁用或空间已删除）。 */
    public boolean empty() {
        return !unrestricted && spaceIds.isEmpty();
    }
}
