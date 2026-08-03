package com.yumg.starter.modules.users.application;

public record AvatarContent(String contentType, long sizeBytes, String etag,
                            byte[] bytes, boolean fallback) {
    public AvatarContent {
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
}
