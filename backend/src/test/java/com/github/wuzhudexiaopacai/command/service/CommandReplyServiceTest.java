package com.github.wuzhudexiaopacai.command.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.command.entity.DeviceCommand;
import com.github.wuzhudexiaopacai.command.mapper.DeviceCommandMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CommandReplyServiceTest {

    private DeviceCommandMapper mapper;
    private CommandReplyService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DeviceCommandMapper.class);
        service = new CommandReplyService(mapper, new ObjectMapper(), new SimpleMeterRegistry());
        when(mapper.updateById((DeviceCommand) any())).thenReturn(1);
    }

    @Test
    void successReply_updatesStatusAndResult() throws Exception {
        DeviceCommand command = command("SENT");
        when(mapper.selectOne(any())).thenReturn(command);
        String payload = "{\"id\":\"req-1\",\"code\":0,\"result\":{\"interval\":5}}";

        service.onReply("default", "dev-1", payload.getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<DeviceCommand> captor = ArgumentCaptor.forClass(DeviceCommand.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("SUCCESS", captor.getValue().getStatus());
        assertEquals(5, captor.getValue().getResult().get("interval"));
    }

    @Test
    void failureReply_marksFailedWithError() {
        when(mapper.selectOne(any())).thenReturn(command("SENT"));
        String payload = "{\"id\":\"req-1\",\"code\":500,\"error\":\"device busy\"}";

        service.onReply("default", "dev-1", payload.getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<DeviceCommand> captor = ArgumentCaptor.forClass(DeviceCommand.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals("device busy", captor.getValue().getError());
    }

    @Test
    void duplicateReply_isIgnored() {
        when(mapper.selectOne(any())).thenReturn(command("SUCCESS"));

        service.onReply("default", "dev-1",
                "{\"id\":\"req-1\",\"code\":0}".getBytes(StandardCharsets.UTF_8));

        verify(mapper, never()).updateById((DeviceCommand) any());
    }

    @Test
    void unknownRequest_isIgnored() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.onReply("default", "dev-1",
                "{\"id\":\"ghost\",\"code\":0}".getBytes(StandardCharsets.UTF_8));

        verify(mapper, never()).updateById((DeviceCommand) any());
    }

    @Test
    void replyWithoutId_isIgnored() {
        service.onReply("default", "dev-1", "{}".getBytes(StandardCharsets.UTF_8));

        verify(mapper, never()).selectOne(any());
    }

    private DeviceCommand command(String status) {
        DeviceCommand command = new DeviceCommand();
        command.setId(1L);
        command.setDeviceId("dev-1");
        command.setRequestId("req-1");
        command.setStatus(status);
        return command;
    }
}
