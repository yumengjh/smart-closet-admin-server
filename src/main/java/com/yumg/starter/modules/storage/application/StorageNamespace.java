package com.yumg.starter.modules.storage.application;

public enum StorageNamespace {
    AVATAR_GENERATED("avatar-generated"),
    AVATAR_UPLOADED("avatar-uploaded"),
    AVATAR_CANDIDATE("avatar-candidate");

    private final String directory;

    StorageNamespace(String directory) { this.directory = directory; }

    public String directory() { return directory; }
}
