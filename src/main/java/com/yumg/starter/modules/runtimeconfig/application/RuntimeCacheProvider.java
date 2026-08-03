package com.yumg.starter.modules.runtimeconfig.application;

public interface RuntimeCacheProvider {
    String code();
    String displayName();
    int order();
    RuntimeCacheStatus status();
    RuntimeCacheStatus forceRefresh();
    boolean refreshIfChanged();
}
