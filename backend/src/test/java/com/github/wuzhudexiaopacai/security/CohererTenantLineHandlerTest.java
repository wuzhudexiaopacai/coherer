package com.github.wuzhudexiaopacai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CohererTenantLineHandlerTest {

    private final CohererTenantLineHandler handler = new CohererTenantLineHandler();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void getTenantId_returnsCurrentTenant() {
        TenantContext.set("tenant-a");

        assertThat(handler.getTenantId()).isInstanceOf(StringValue.class);
        assertThat(((StringValue) handler.getTenantId()).getValue()).isEqualTo("tenant-a");
        assertThat(handler.getTenantIdColumn()).isEqualTo("tenant_id");
    }

    @Test
    void getTenantId_withoutContext_failsClosed() {
        assertThatThrownBy(handler::getTenantId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ignoreTable_onlyTenantTableAndExplicitBypass() {
        TenantContext.set("tenant-a");

        assertThat(handler.ignoreTable("tenant")).isTrue();
        assertThat(handler.ignoreTable("TENANT")).isTrue();
        assertThat(handler.ignoreTable("device")).isFalse();

        TenantContext.runBypass(() -> assertThat(handler.ignoreTable("device")).isTrue());
        assertThat(handler.ignoreTable("device")).isFalse();
    }
}
