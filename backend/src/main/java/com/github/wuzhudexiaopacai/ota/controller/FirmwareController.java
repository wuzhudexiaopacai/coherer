package com.github.wuzhudexiaopacai.ota.controller;

import com.github.wuzhudexiaopacai.audit.AuditLogService;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import com.github.wuzhudexiaopacai.ota.service.FirmwareService;
import com.github.wuzhudexiaopacai.ota.vo.FirmwareVO;
import com.github.wuzhudexiaopacai.security.DataScopeService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ota/firmwares")
@RequiredArgsConstructor
public class FirmwareController {

    private final FirmwareService firmwareService;
    private final DataScopeService dataScopeService;
    private final AuditLogService auditLogService;

    /** 上传固件包；同产品版本号唯一。 */
    @PostMapping
    public ApiResponse<FirmwareVO> upload(@RequestParam Long productId,
                                          @RequestParam String version,
                                          @RequestPart("file") MultipartFile file,
                                          Authentication authentication) {
        dataScopeService.requireUnrestricted("固件管理");
        FirmwareVO vo = firmwareService.upload(productId, version, file,
                authentication.getName());
        auditLogService.record("FIRMWARE_UPLOAD", "firmware", Map.of(
                "id", String.valueOf(vo.getId()),
                "productId", String.valueOf(productId),
                "version", version,
                "sha256", vo.getSha256()));
        return ApiResponse.ok(vo);
    }

    @GetMapping
    public ApiResponse<List<FirmwareVO>> list(@RequestParam Long productId) {
        return ApiResponse.ok(firmwareService.listByProduct(productId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        dataScopeService.requireUnrestricted("固件管理");
        firmwareService.delete(id);
        auditLogService.record("FIRMWARE_DELETE", "firmware", Map.of("id", String.valueOf(id)));
        return ApiResponse.ok();
    }
}
