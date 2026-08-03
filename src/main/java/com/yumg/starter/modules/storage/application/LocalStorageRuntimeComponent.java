package com.yumg.starter.modules.storage.application;

import com.yumg.starter.modules.runtimeconfig.application.ComponentCheckResult;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeComponent;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCheckKind;
import com.yumg.starter.modules.storage.infrastructure.LocalStorageBackend;
import org.springframework.stereotype.Component;

@Component
public class LocalStorageRuntimeComponent implements RuntimeComponent {
    public static final String CODE = "LOCAL_STORAGE";
    private final LocalStorageBackend storage;

    public LocalStorageRuntimeComponent(LocalStorageBackend storage) { this.storage = storage; }
    @Override public String code() { return CODE; }
    @Override public String displayName() { return "本地文件存储"; }
    @Override public RuntimeCheckKind kind() { return RuntimeCheckKind.LOCAL_RESOURCE; }
    @Override public ComponentCheckResult check() {
        var result = storage.checkHealth();
        return result.up() ? ComponentCheckResult.up(result.message())
                : ComponentCheckResult.error(result.message());
    }
}
