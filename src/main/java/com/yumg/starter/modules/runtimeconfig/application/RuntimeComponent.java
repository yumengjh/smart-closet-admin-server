package com.yumg.starter.modules.runtimeconfig.application;

public interface RuntimeComponent {
    String code();
    String displayName();
    RuntimeCheckKind kind();
    ComponentCheckResult check();
    default boolean enabled(RuntimeSettingService settings) { return true; }
}
