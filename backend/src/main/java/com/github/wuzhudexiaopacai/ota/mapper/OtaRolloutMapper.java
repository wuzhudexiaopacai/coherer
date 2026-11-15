package com.github.wuzhudexiaopacai.ota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.wuzhudexiaopacai.ota.entity.OtaRollout;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface OtaRolloutMapper extends BaseMapper<OtaRollout> {

    /** 多实例巡检时串行化同一批次的推进。 */
    @Select("SELECT * FROM ota_rollout WHERE id = #{id} FOR UPDATE")
    OtaRollout selectForUpdate(@Param("id") Long id);
}
