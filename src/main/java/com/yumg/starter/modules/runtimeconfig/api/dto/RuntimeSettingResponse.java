package com.yumg.starter.modules.runtimeconfig.api.dto;

import com.yumg.starter.entities.RuntimeSetting;

public record RuntimeSettingResponse(String key, String displayName, String description,
                                     String group, String type, String value,
                                     Integer minValue, Integer maxValue, boolean dangerous) {
    public static RuntimeSettingResponse from(RuntimeSetting setting, String displayName,
                                              String description, String group,
                                              Integer minValue, Integer maxValue,
                                              boolean dangerous) {
        return new RuntimeSettingResponse(setting.getKey(), displayName, description, group,
                setting.getValueType(), setting.getValue(), minValue, maxValue, dangerous);
    }
}
