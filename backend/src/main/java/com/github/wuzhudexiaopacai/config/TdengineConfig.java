package com.github.wuzhudexiaopacai.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** TDengine 独立 JDBC 连接（WebSocket 协议，支持参数绑定）。 */
@Configuration
public class TdengineConfig {

    @Bean("tdengineJdbcTemplate")
    public JdbcTemplate tdengineJdbcTemplate(
            @Value("${coherer.tdengine.url}") String url,
            @Value("${coherer.tdengine.username}") String username,
            @Value("${coherer.tdengine.password}") String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver");
        config.setMaximumPoolSize(2);
        config.setPoolName("tdengine-pool");
        // TDengine 暂不可用时不让应用启动失败，后续请求按需重连
        config.setInitializationFailTimeout(-1);
        return new JdbcTemplate(new HikariDataSource(config));
    }
}
