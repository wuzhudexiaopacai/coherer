package com.github.wuzhudexiaopacai.ota.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.mapper.DeviceMapper;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.ota.dto.OtaRolloutCreateRequest;
import com.github.wuzhudexiaopacai.ota.entity.Firmware;
import com.github.wuzhudexiaopacai.ota.entity.OtaRollout;
import com.github.wuzhudexiaopacai.ota.entity.OtaRolloutDevice;
import com.github.wuzhudexiaopacai.ota.entity.OtaTask;
import com.github.wuzhudexiaopacai.ota.event.OtaTaskFinishedEvent;
import com.github.wuzhudexiaopacai.ota.mapper.FirmwareMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaRolloutDeviceMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaRolloutMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaTaskMapper;
import com.github.wuzhudexiaopacai.project.service.ProjectScopeService;
import com.github.wuzhudexiaopacai.security.DataScope;
import com.github.wuzhudexiaopacai.space.service.SpaceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OtaRolloutServiceTest {

    private OtaRolloutMapper rolloutMapper;
    private OtaRolloutDeviceMapper rolloutDeviceMapper;
    private OtaTaskMapper taskMapper;
    private FirmwareMapper firmwareMapper;
    private FirmwareService firmwareService;
    private DeviceLookupService deviceLookupService;
    private DeviceMapper deviceMapper;
    private SpaceService spaceService;
    private ProjectScopeService projectScopeService;
    private OtaService otaService;
    private OtaRolloutService service;
    private Firmware firmware;

    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(OtaRollout.class, OtaRolloutDevice.class,
                OtaTask.class, Firmware.class, Device.class)) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
        }
        rolloutMapper = mock(OtaRolloutMapper.class);
        rolloutDeviceMapper = mock(OtaRolloutDeviceMapper.class);
        taskMapper = mock(OtaTaskMapper.class);
        firmwareMapper = mock(FirmwareMapper.class);
        firmwareService = mock(FirmwareService.class);
        deviceLookupService = mock(DeviceLookupService.class);
        deviceMapper = mock(DeviceMapper.class);
        spaceService = mock(SpaceService.class);
        projectScopeService = mock(ProjectScopeService.class);
        otaService = mock(OtaService.class);
        service = new OtaRolloutService(rolloutMapper, rolloutDeviceMapper, taskMapper,
                firmwareMapper, firmwareService, deviceLookupService, deviceMapper,
                spaceService, projectScopeService, otaService, new SimpleMeterRegistry());
        when(projectScopeService.resolveSpaceIds(any(), any(), any()))
                .thenReturn(List.of(100L, 101L, 200L));

        firmware = new Firmware();
        firmware.setId(10L);
        firmware.setProductId(1L);
        firmware.setTenantId("default");
        firmware.setVersion("1.2.0");
        when(firmwareService.require(10L)).thenReturn(firmware);
        when(rolloutMapper.insert(any(OtaRollout.class))).thenAnswer(invocation -> {
            ((OtaRollout) invocation.getArgument(0)).setId(50L);
            return 1;
        });
        when(rolloutMapper.updateById(any(OtaRollout.class))).thenReturn(1);
        when(rolloutDeviceMapper.insert(any(OtaRolloutDevice.class))).thenReturn(1);
        when(rolloutDeviceMapper.update(any(), any())).thenReturn(1);
        when(taskMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void create_deviceTargets_assignsBatchesBySize() {
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device("dev-1", 100L));
        when(deviceLookupService.getByDeviceId("dev-2")).thenReturn(device("dev-2", 100L));
        when(deviceLookupService.getByDeviceId("dev-3")).thenReturn(device("dev-3", 100L));
        AtomicReference<OtaRollout> saved = new AtomicReference<>();
        when(rolloutMapper.insert(any(OtaRollout.class))).thenAnswer(invocation -> {
            OtaRollout rollout = invocation.getArgument(0);
            rollout.setId(50L);
            saved.set(rollout);
            return 1;
        });
        List<OtaRolloutDevice> members = new ArrayList<>();
        when(rolloutDeviceMapper.insert(any(OtaRolloutDevice.class))).thenAnswer(invocation -> {
            members.add(invocation.getArgument(0));
            return 1;
        });
        when(rolloutMapper.selectById(50L)).thenAnswer(invocation -> saved.get());
        when(rolloutDeviceMapper.selectList(any())).thenReturn(members);
        when(firmwareMapper.selectById(10L)).thenReturn(firmware);
        when(deviceMapper.selectList(any())).thenReturn(List.of(
                device("dev-1", 100L), device("dev-2", 100L), device("dev-3", 100L)));

        OtaRolloutCreateRequest request = request("DEVICE", 2);
        request.setDeviceIds(List.of("dev-1", "dev-2", "dev-3"));
        var vo = service.create(request, scope(100L), "admin");

        assertEquals(3, vo.getTotalCount());
        assertEquals(2, vo.getTotalBatches());
        assertEquals("PENDING", vo.getStatus());
        assertEquals(List.of(1, 1, 2), members.stream()
                .map(OtaRolloutDevice::getBatchNo).sorted().toList());
    }

    @Test
    void create_deviceOutsideScope_isRejected() {
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device("dev-1", 200L));

        OtaRolloutCreateRequest request = request("DEVICE", 10);
        request.setDeviceIds(List.of("dev-1"));

        BizException exception = assertThrows(BizException.class,
                () -> service.create(request, scope(100L), "admin"));
        assertEquals(403, exception.getCode());
        verify(rolloutMapper, never()).insert(any(OtaRollout.class));
    }

    @Test
    void create_allTargets_queriesProductDevicesInScope() {
        when(deviceMapper.selectList(any())).thenReturn(List.of(device("dev-1", 100L)));
        AtomicReference<OtaRollout> saved = new AtomicReference<>();
        when(rolloutMapper.insert(any(OtaRollout.class))).thenAnswer(invocation -> {
            OtaRollout rollout = invocation.getArgument(0);
            rollout.setId(50L);
            saved.set(rollout);
            return 1;
        });
        when(rolloutMapper.selectById(50L)).thenAnswer(invocation -> saved.get());
        when(rolloutDeviceMapper.selectList(any())).thenReturn(List.of());
        when(firmwareMapper.selectById(10L)).thenReturn(firmware);

        var vo = service.create(request("ALL", 10), scope(100L), "admin");

        assertEquals(1, vo.getTotalCount());
        assertEquals("ALL", vo.getTargetType());
    }

    @Test
    void create_noEligibleDevices_isRejected() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        BizException exception = assertThrows(BizException.class,
                () -> service.create(request("ALL", 10), scope(100L), "admin"));
        assertEquals(409, exception.getCode());
    }

    @Test
    void pause_runningRollout_setsPaused() {
        OtaRollout rollout = rollout(50L, "RUNNING");
        when(rolloutMapper.selectForUpdate(50L)).thenReturn(rollout);
        when(rolloutMapper.selectById(50L)).thenReturn(rollout);
        when(rolloutDeviceMapper.selectList(any())).thenReturn(List.of());
        when(firmwareMapper.selectById(10L)).thenReturn(firmware);

        var vo = service.pause(50L, scope(100L), "admin", "变更窗口");

        assertEquals("PAUSED", vo.getStatus());
        assertEquals("变更窗口", vo.getPauseReason());
    }

    @Test
    void resume_pausedRollout_setsRunning() {
        OtaRollout rollout = rollout(50L, "PAUSED");
        rollout.setPauseReason("人工暂停");
        when(rolloutMapper.selectForUpdate(50L)).thenReturn(rollout);
        when(rolloutMapper.selectById(50L)).thenReturn(rollout);
        when(rolloutDeviceMapper.selectList(any())).thenReturn(List.of());
        when(firmwareMapper.selectById(10L)).thenReturn(firmware);

        var vo = service.resume(50L, scope(100L), "admin");

        assertEquals("RUNNING", vo.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(vo.getPauseReason());
        verify(rolloutMapper).update(any(), any());
    }

    @Test
    void pause_terminalRollout_isRejected() {
        OtaRollout rollout = rollout(50L, "SUCCESS");
        when(rolloutMapper.selectForUpdate(50L)).thenReturn(rollout);

        BizException exception = assertThrows(BizException.class,
                () -> service.pause(50L, scope(100L), "admin", null));
        assertEquals(409, exception.getCode());
    }

    @Test
    void cancel_pendingRollout_skipsRemainingDevices() {
        OtaRollout rollout = rollout(50L, "RUNNING");
        when(rolloutMapper.selectForUpdate(50L)).thenReturn(rollout);
        when(rolloutMapper.selectById(50L)).thenReturn(rollout);
        when(rolloutDeviceMapper.selectList(any())).thenReturn(List.of());
        when(firmwareMapper.selectById(10L)).thenReturn(firmware);

        var vo = service.cancel(50L, scope(100L), "admin");

        assertEquals("CANCELED", vo.getStatus());
        verify(rolloutDeviceMapper).update(any(), any());
    }

    @Test
    void advance_pendingRollout_dispatchesNextBatch() {
        OtaRollout rollout = rollout(50L, "PENDING");
        rollout.setCurrentBatch(0);
        rollout.setReviewedBatch(0);
        OtaRolloutDevice member1 = member("dev-1", 1, "PENDING");
        OtaRolloutDevice member2 = member("dev-2", 1, "PENDING");
        when(rolloutMapper.selectForUpdate(50L)).thenReturn(rollout);
        when(rolloutDeviceMapper.selectOne(any())).thenReturn(member1);
        when(rolloutDeviceMapper.selectList(any())).thenReturn(
                List.of(), List.of(member1, member2), List.of(
                        member("dev-1", 1, "RUNNING"), member("dev-2", 1, "RUNNING")));
        when(deviceLookupService.getByDeviceId("dev-1")).thenReturn(device("dev-1", 100L));
        when(deviceLookupService.getByDeviceId("dev-2")).thenReturn(device("dev-2", 100L));
        when(otaService.startForDevice(any(), any(), any(), eq(50L), eq(1), anyInt()))
                .thenReturn(new OtaService.OtaTaskDispatch(1L, "SENT", null));

        service.advance(50L);

        assertEquals("RUNNING", rollout.getStatus());
        assertEquals(1, rollout.getCurrentBatch());
        verify(otaService, times(2)).startForDevice(any(), any(), any(), eq(50L), eq(1), anyInt());
    }

    @Test
    void advance_failureRateAboveThreshold_pauses() {
        OtaRollout rollout = rollout(50L, "RUNNING");
        rollout.setCurrentBatch(1);
        rollout.setReviewedBatch(0);
        rollout.setFailureThresholdPercent(20);
        when(rolloutMapper.selectForUpdate(50L)).thenReturn(rollout);
        when(rolloutDeviceMapper.selectCount(any())).thenReturn(0L, 2L);
        when(rolloutDeviceMapper.selectList(any())).thenReturn(
                List.of(),
                List.of(member("dev-1", 1, "SUCCESS"), member("dev-2", 1, "FAILED")));

        service.advance(50L);

        assertEquals("PAUSED", rollout.getStatus());
        assertNotNull(rollout.getPauseReason());
        assertTrue(rollout.getPauseReason().contains("失败率"));
        assertEquals(1, rollout.getReviewedBatch());
    }

    @Test
    void advance_allSuccess_finalizesRollout() {
        OtaRollout rollout = rollout(50L, "RUNNING");
        rollout.setCurrentBatch(1);
        rollout.setReviewedBatch(1);
        rollout.setTotalCount(2);
        when(rolloutMapper.selectForUpdate(50L)).thenReturn(rollout);
        when(rolloutDeviceMapper.selectCount(any())).thenReturn(0L, 0L);
        when(rolloutDeviceMapper.selectList(any())).thenReturn(
                List.of(),
                List.of(member("dev-1", 1, "SUCCESS"), member("dev-2", 1, "SUCCESS")));

        service.advance(50L);

        assertEquals("SUCCESS", rollout.getStatus());
        assertNotNull(rollout.getFinishedAt());
    }

    @Test
    void onTaskFinished_updatesMemberAndCounters() {
        OtaRollout rollout = rollout(50L, "RUNNING");
        when(rolloutMapper.selectById(50L)).thenReturn(rollout);
        when(rolloutDeviceMapper.selectList(any())).thenReturn(
                List.of(member("dev-1", 1, "SUCCESS")));

        service.onTaskFinished(new OtaTaskFinishedEvent(1L, 50L, "dev-1", "SUCCESS", null));

        verify(rolloutDeviceMapper).update(any(), any());
        assertEquals(1, rollout.getSuccessCount());
    }

    private OtaRolloutCreateRequest request(String targetType, int batchSize) {
        OtaRolloutCreateRequest request = new OtaRolloutCreateRequest();
        request.setFirmwareId(10L);
        request.setName("灰度批次");
        request.setTargetType(targetType);
        request.setSpaceId(100L);
        request.setBatchSize(batchSize);
        request.setFailureThresholdPercent(20);
        request.setAutoAdvance(true);
        return request;
    }

    private OtaRollout rollout(Long id, String status) {
        OtaRollout rollout = new OtaRollout();
        rollout.setId(id);
        rollout.setFirmwareId(10L);
        rollout.setProductId(1L);
        rollout.setName("灰度批次");
        rollout.setTargetType("SPACE");
        rollout.setTargetSpaceId(100L);
        rollout.setStatus(status);
        rollout.setBatchSize(10);
        rollout.setFailureThresholdPercent(20);
        rollout.setAutoAdvance(true);
        rollout.setCurrentBatch(0);
        rollout.setReviewedBatch(0);
        rollout.setTotalCount(2);
        rollout.setPendingCount(2);
        rollout.setRunningCount(0);
        rollout.setSuccessCount(0);
        rollout.setFailedCount(0);
        rollout.setCreatedBy("admin");
        rollout.setCreatedAt(OffsetDateTime.now());
        rollout.setUpdatedAt(OffsetDateTime.now());
        return rollout;
    }

    private OtaRolloutDevice member(String deviceId, int batchNo, String status) {
        OtaRolloutDevice member = new OtaRolloutDevice();
        member.setRolloutId(50L);
        member.setDeviceId(deviceId);
        member.setBatchNo(batchNo);
        member.setStatus(status);
        return member;
    }

    private Device device(String deviceId, Long spaceId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setProductId(1L);
        device.setSpaceId(spaceId);
        device.setTenantId("default");
        device.setStatus("ONLINE");
        return device;
    }

    private DataScope scope(Long spaceId) {
        return DataScope.of(spaceId, List.of(spaceId));
    }
}
