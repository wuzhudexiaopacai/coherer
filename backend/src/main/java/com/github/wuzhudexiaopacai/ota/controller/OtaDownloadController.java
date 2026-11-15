package com.github.wuzhudexiaopacai.ota.controller;

import com.github.wuzhudexiaopacai.ota.entity.Firmware;
import com.github.wuzhudexiaopacai.ota.service.FirmwareService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 设备侧固件下载：短期 HMAC 签名鉴权，支持 HTTP Range 断点续传。
 * 本地文件与 S3 对象都通过同一 offset/length 语义读取。
 */
@RestController
@RequestMapping("/api/ota/download")
@RequiredArgsConstructor
public class OtaDownloadController {

    private final FirmwareService firmwareService;

    @GetMapping("/{firmwareId}")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable Long firmwareId,
            @RequestParam String deviceId,
            @RequestParam long expires,
            @RequestParam String signature,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        Firmware firmware = firmwareService.verifyDownload(firmwareId, deviceId, expires, signature);
        long total = firmware.getSizeBytes() == null ? 0 : firmware.getSizeBytes();
        if (total <= 0) {
            return ResponseEntity.notFound().build();
        }

        long start = 0;
        long length = total;
        boolean partial = false;
        if (StringUtils.hasText(rangeHeader)) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                if (ranges.size() != 1) {
                    return rangeNotSatisfiable(total);
                }
                HttpRange range = ranges.get(0);
                start = range.getRangeStart(total);
                long end = range.getRangeEnd(total);
                if (start < 0 || start >= total || end < start) {
                    return rangeNotSatisfiable(total);
                }
                length = end - start + 1;
                partial = true;
            } catch (IllegalArgumentException e) {
                return rangeNotSatisfiable(total);
            }
        }

        InputStream input = firmwareService.openStream(firmware, start, length);
        StreamingResponseBody body = output -> {
            try (input) {
                input.transferTo(output);
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(length);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(firmware.getFileName(), StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl(CacheControl.noStore());
        if (partial) {
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + start + "-" + (start + length - 1) + "/" + total);
        }
        return new ResponseEntity<>(body, headers,
                partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    }

    private ResponseEntity<StreamingResponseBody> rangeNotSatisfiable(long total) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                .build();
    }
}
