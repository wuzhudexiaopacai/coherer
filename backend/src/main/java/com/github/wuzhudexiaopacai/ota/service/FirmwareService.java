package com.github.wuzhudexiaopacai.ota.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.wuzhudexiaopacai.common.BizException;
import com.github.wuzhudexiaopacai.device.entity.Device;
import com.github.wuzhudexiaopacai.device.service.DeviceLookupService;
import com.github.wuzhudexiaopacai.ota.entity.Firmware;
import com.github.wuzhudexiaopacai.ota.entity.OtaTask;
import com.github.wuzhudexiaopacai.ota.mapper.FirmwareMapper;
import com.github.wuzhudexiaopacai.ota.mapper.OtaTaskMapper;
import com.github.wuzhudexiaopacai.ota.storage.FirmwareStorage;
import com.github.wuzhudexiaopacai.ota.vo.FirmwareVO;
import com.github.wuzhudexiaopacai.product.entity.Product;
import com.github.wuzhudexiaopacai.product.mapper.ProductMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class FirmwareService extends ServiceImpl<FirmwareMapper, Firmware> {

    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z._-]{1,32}$");

    private final ProductMapper productMapper;
    private final DeviceLookupService deviceLookupService;
    private final FirmwareStorage storage;
    private final OtaDownloadSigner downloadSigner;
    private final OtaTaskMapper otaTaskMapper;

    @Value("${coherer.ota.public-base-url:https://localhost:8443}")
    private String publicBaseUrl;

    @Value("${coherer.ota.download-ttl-seconds:600}")
    private long downloadTtlSeconds;

    @Transactional(rollbackFor = Exception.class)
    public FirmwareVO upload(Long productId, String version, MultipartFile file, String operator) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new BizException(400, "版本号只能包含字母、数字、点、下划线、短横线，最长 32");
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(404, "产品不存在");
        }
        Long exists = baseMapper.selectCount(Wrappers.<Firmware>lambdaQuery()
                .eq(Firmware::getProductId, productId)
                .eq(Firmware::getVersion, version));
        if (exists != null && exists > 0) {
            throw new BizException(409, "该产品版本已存在");
        }
        FirmwareStorage.Stored stored;
        try {
            stored = storage.store(productId, file);
        } catch (Exception e) {
            throw new BizException(500, "固件保存失败: " + e.getMessage());
        }
        Firmware firmware = new Firmware();
        firmware.setTenantId(product.getTenantId() == null ? "default" : product.getTenantId());
        firmware.setProductId(productId);
        firmware.setVersion(version);
        firmware.setFileName(stored.fileName());
        firmware.setContentType(file.getContentType());
        firmware.setSizeBytes(stored.size());
        firmware.setSha256(stored.sha256());
        firmware.setStoragePath(stored.relativePath());
        firmware.setStorageType(storage.type());
        firmware.setCreatedBy(operator);
        firmware.setCreatedAt(OffsetDateTime.now());
        try {
            save(firmware);
        } catch (RuntimeException e) {
            // 元数据写入失败时回滚已落盘/已上传文件，避免孤儿对象占满存储
            storage.delete(storage.type(), stored.relativePath());
            throw e;
        }
        return toVO(firmware);
    }

    public List<FirmwareVO> listByProduct(Long productId) {
        return list(Wrappers.<Firmware>lambdaQuery()
                .eq(Firmware::getProductId, productId)
                .orderByDesc(Firmware::getCreatedAt))
                .stream()
                .map(this::toVO)
                .toList();
    }

    public Firmware require(Long id) {
        Firmware firmware = getById(id);
        if (firmware == null) {
            throw new BizException(404, "固件不存在");
        }
        return firmware;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Firmware firmware = require(id);
        Long active = otaTaskMapper.selectCount(Wrappers.<OtaTask>lambdaQuery()
                .eq(OtaTask::getFirmwareId, id)
                .in(OtaTask::getStatus, List.of("PENDING", "SENT", "DOWNLOADING")));
        if (active != null && active > 0) {
            throw new BizException(409, "该固件仍有进行中的升级任务，暂不能删除");
        }
        removeById(id);
        storage.delete(firmware.getStorageType(), firmware.getStoragePath());
    }

    public String downloadUrl(Firmware firmware, String deviceId) {
        long expires = System.currentTimeMillis() + downloadTtlSeconds * 1000L;
        String signature = downloadSigner.sign(firmware.getId(), deviceId, expires);
        return publicBaseUrl + "/api/ota/download/" + firmware.getId()
                + "?deviceId=" + deviceId + "&expires=" + expires + "&signature=" + signature;
    }

    public Download download(Long firmwareId, String deviceId, long expires, String signature) {
        if (!downloadSigner.verify(firmwareId, deviceId, expires, signature)) {
            throw new BizException(403, "下载签名无效或已过期");
        }
        Firmware firmware = require(firmwareId);
        Device device = deviceLookupService.getByDeviceId(deviceId);
        if (device == null || !firmware.getProductId().equals(device.getProductId())) {
            throw new BizException(403, "设备与固件产品不匹配");
        }
        if ("DISABLED".equals(device.getStatus())) {
            throw new BizException(403, "设备已禁用");
        }
        try {
            return new Download(firmware,
                    storage.load(firmware.getStorageType(), firmware.getStoragePath()));
        } catch (Exception e) {
            throw new BizException(404, "固件文件不存在");
        }
    }

    public record Download(Firmware firmware, Resource resource) {
    }

    private FirmwareVO toVO(Firmware firmware) {
        FirmwareVO vo = new FirmwareVO();
        BeanUtils.copyProperties(firmware, vo);
        return vo;
    }
}
