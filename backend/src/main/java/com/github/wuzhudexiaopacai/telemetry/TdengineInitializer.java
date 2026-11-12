package com.github.wuzhudexiaopacai.telemetry;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** TDengine 库与超级表初始化（M2 通用模型：超级表 + 每设备子表）。 */
@Component
public class TdengineInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TdengineInitializer.class);

    private final JdbcTemplate jdbc;

    public TdengineInitializer(@Qualifier("tdengineJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                        product_id BIGINT
                    )
                    """);
            log.info("TDengine 初始化完成（database=coherer, stable=stb_telemetry）");
        } catch (Exception e) {
            log.error("TDengine 初始化失败（确认 tdengine 容器已启动）: {}", e.getMessage());
        }
    }
}
