package com.github.wuzhudexiaopacai.telemetry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** TDengine 库与超级表初始化（M2 通用模型：超级表 + 每设备子表）。 */
@Component
public class TdengineInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TdengineInitializer.class);

    private final JdbcTemplate jdbc;
    private final DeviceMapper deviceMapper;

    @Value("${coherer.tdengine.keep-days:365}")
    private int keepDays;

    public TdengineInitializer(@Qualifier("tdengineJdbcTemplate") JdbcTemplate jdbc,
                               DeviceMapper deviceMapper) {
        this.jdbc = jdbc;
        this.deviceMapper = deviceMapper;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            jdbc.execute("CREATE DATABASE IF NOT EXISTS coherer");
            // TODO M3：按物模型属性生成列式超级表（当前用 payload 泛化存储）
            jdbc.execute("""
                    CREATE STABLE IF NOT EXISTS coherer.stb_telemetry (
                        ts TIMESTAMP,
                        payload NCHAR(2048)
                    ) TAGS (
                        device_id NCHAR(64),
                        tenant_id NCHAR(32),
                        product_id BIGINT,
                        space_id BIGINT
                    )
                    """);
            applyKeepDays();
            ensureSpaceTag();
            log.info("TDengine 初始化完成（database=coherer, stable=stb_telemetry）");
        } catch (Exception e) {
            log.error("TDengine 初始化失败（确认 tdengine 容器已启动）: {}", e.getMessage());
        }
    }

    /** 数据保留期：避免遥测无限增长。 */
    private void applyKeepDays() {
        try {
            jdbc.execute("ALTER DATABASE coherer KEEP " + Math.max(keepDays, 1));
            log.info("TDengine 保留期设置为 {} 天", keepDays);
        } catch (Exception e) {
            log.warn("设置 TDengine 保留期失败（可能版本不支持）: {}", e.getMessage());
        }
    }

    /** 旧库升级：stb_telemetry 没有 space_id tag 时补列并回填已有子表。 */
    private void ensureSpaceTag() {
        try {
            jdbc.execute("ALTER STABLE coherer.stb_telemetry ADD TAG space_id BIGINT");
            log.info("TDengine 超级表已增加 space_id tag");
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("Tag already exists")) {
                // 迁移已完成，正常路径，不刷 WARN
                return;
            }
            log.warn("增加 space_id tag 失败: {}", message);
            return;
        }
        List<Device> devices = deviceMapper.selectList(Wrappers.<Device>lambdaQuery()
                .select(Device::getDeviceId, Device::getSpaceId));
        int updated = 0;
        for (Device device : devices) {
            if (device.getSpaceId() == null) {
                continue;
            }
            String subTable = "d_" + device.getDeviceId().replaceAll("[^A-Za-z0-9_]", "_");
            try {
                jdbc.execute("ALTER TABLE coherer." + subTable
                        + " SET TAG space_id = " + device.getSpaceId());
                updated++;
            } catch (Exception e) {
                log.debug("回填子表 space_id 失败 deviceId={} err={}",
                        device.getDeviceId(), e.getMessage());
            }
        }
        log.info("TDengine 子表 space_id 回填完成：{}/{}", updated, devices.size());
    }
}
