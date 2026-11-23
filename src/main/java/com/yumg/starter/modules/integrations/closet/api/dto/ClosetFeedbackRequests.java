package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bodies for Fish Closet user feedback (smart-closet-server docs/design/2026-11-23-用户反馈：匿名提交与后台处理设计.md §6). */
public final class ClosetFeedbackRequests {
    private ClosetFeedbackRequests() {}

    /** Absent fields stay; an empty reply or note clears it. */
    public record Edit(
            @NotNull Long revision,
            @Pattern(regexp = "NEW|PROCESSING|RESOLVED|CLOSED") String status,
            @Size(max = 1000) String reply,
            @Size(max = 1000) String note) {}

    public record Settings(@NotNull Boolean anonymousEnabled, @NotNull Long revision) {}
}
