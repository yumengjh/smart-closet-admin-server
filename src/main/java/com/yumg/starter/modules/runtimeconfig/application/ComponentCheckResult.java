package com.yumg.starter.modules.runtimeconfig.application;

public record ComponentCheckResult(boolean up, String message) {
    public static ComponentCheckResult up(String message) { return new ComponentCheckResult(true, message); }
    public static ComponentCheckResult error(String message) { return new ComponentCheckResult(false, message); }
}
