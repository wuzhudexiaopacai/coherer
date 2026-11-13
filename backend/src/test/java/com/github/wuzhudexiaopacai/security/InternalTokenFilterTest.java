package com.github.wuzhudexiaopacai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalTokenFilterTest {

    private static final String TOKEN = "internal-test-token";

    @Test
    void missingToken_returns401AndStopsChain() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/auth/mqtt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertFalse(invoked.get());
    }

    @Test
    void wrongToken_returns401() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/auth/mqtt");
        request.addHeader("X-Internal-Token", "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(401, response.getStatus());
    }

    @Test
    void validToken_passesThrough() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/auth/mqtt");
        request.addHeader("X-Internal-Token", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void nonInternalPath_skipsTokenCheck() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertTrue(invoked.get());
    }
}
