package com.github.wuzhudexiaopacai.ota.controller;

import com.github.wuzhudexiaopacai.ota.service.FirmwareService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备侧固件下载：使用短期 HMAC 签名鉴权，不依赖用户 JWT。
 * URL 由平台在 OTA 指令中下发，过期或设备/固件不匹配即拒绝。
 */
@RestController
@RequestMapping("/api/ota/download")
@RequiredArgsConstructor
public class OtaDownloadController {

    private final FirmwareService firmwareService;

    @GetMapping("/{firmwareId}")
    public ResponseEntity<Resource> download(@PathVariable Long firmwareId,
                                             @RequestParam String deviceId,
                                             @RequestParam long expires,
                                             @RequestParam String signature) {
        FirmwareService.Download download =
                firmwareService.download(firmwareId, deviceId, expires, signature);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.firmware().getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.firmware().getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.noStore())
                .body(download.resource());
    }
}
