package com.yumg.starter.modules.users.application;

public record ProcessedAvatar(String contentType, byte[] png) {
    public ProcessedAvatar {
        png = png.clone();
    }

    @Override
    public byte[] png() {
        return png.clone();
    }
}
