package com.github.wuzhudexiaopacai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void requireTenant_withoutContext_failsClosed() {
        assertThatThrownBy(TenantContext::requireTenant)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户上下文");
    }

    @Test
    void callAs_restoresPreviousTenant() {
        TenantContext.set("tenant-a");

        String nested = TenantContext.callAs("tenant-b", TenantContext::requireTenant);

        assertThat(nested).isEqualTo("tenant-b");
        assertThat(TenantContext.requireTenant()).isEqualTo("tenant-a");
    }

    @Test
    void callAs_withoutPreviousTenant_clearsAfterwards() {
        TenantContext.callAs("tenant-b", TenantContext::requireTenant);

        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void runAs_restoresPreviousTenantWhenActionFails() {
        TenantContext.set("tenant-a");

        assertThatThrownBy(() -> TenantContext.runAs("tenant-b", () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(TenantContext.requireTenant()).isEqualTo("tenant-a");
    }

    @Test
    void nestedBypass_staysActiveUntilOutermostReturns() {
        AtomicBoolean inner = new AtomicBoolean();

        TenantContext.runBypass(() -> TenantContext.runBypass(
                () -> inner.set(TenantContext.bypassed())));

        assertThat(inner).isTrue();
        assertThat(TenantContext.bypassed()).isFalse();
        assertThatThrownBy(TenantContext::requireTenant)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bypassDoesNotLeakWhenActionFails() {
        assertThatThrownBy(() -> TenantContext.runBypass(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.bypassed()).isFalse();
    }

    @Test
    void clear_removesTenantAndBypassState() {
        TenantContext.set("tenant-a");
        TenantContext.callBypass(() -> {
            TenantContext.set("tenant-b");
            return null;
        });

        TenantContext.clear();

        assertThat(TenantContext.get()).isNull();
        assertThat(TenantContext.bypassed()).isFalse();
    }
}
