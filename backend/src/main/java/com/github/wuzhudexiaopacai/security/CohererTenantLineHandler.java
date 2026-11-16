package com.github.wuzhudexiaopacai.security;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.stereotype.Component;

/** MyBatis-Plus 租户条件：自动为业务表追加 tenant_id = 当前租户。 */
@Component
public class CohererTenantLineHandler implements TenantLineHandler {

    @Override
    public Expression getTenantId() {
        return new StringValue(TenantContext.requireTenant());
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // tenant 表自身没有 tenant_id；系统巡检/认证/租户开通显式 bypass
        return TenantContext.bypassed() || "tenant".equalsIgnoreCase(tableName);
    }
}
