package com.github.wuzhudexiaopacai.ota.vo;

import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class OtaRolloutDetailVO extends OtaRolloutVO {

    private List<OtaRolloutDeviceVO> devices;
}
