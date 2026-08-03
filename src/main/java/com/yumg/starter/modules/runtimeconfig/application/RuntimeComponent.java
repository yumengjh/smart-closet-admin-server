package com.yumg.starter.modules.runtimeconfig.application;

public interface RuntimeComponent {
    String code();
    String displayName();
    ComponentCheckResult check();
    default boolean enabled(RuntimeSettingService settings) { return true; }
}
