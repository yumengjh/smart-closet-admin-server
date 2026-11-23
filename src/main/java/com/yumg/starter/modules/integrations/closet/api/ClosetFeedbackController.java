package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.modules.integrations.closet.api.dto.ClosetFeedbackRequests;
import com.yumg.starter.modules.integrations.closet.application.ClosetFeedbackService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Fish Closet user feedback: list, status, reply, internal note, delete and the signed-out switch. */
@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet/feedback")
public class ClosetFeedbackController {
    private final ClosetFeedbackService feedback;
    public ClosetFeedbackController(ClosetFeedbackService feedback) { this.feedback = feedback; }

    @GetMapping @PreAuthorize("hasAuthority('closet:feedback:read')")
    public JsonNode list(@RequestParam MultiValueMap<String, String> query) { return feedback.list(query); }

    @GetMapping("/settings") @PreAuthorize("hasAuthority('closet:feedback:read')")
    public JsonNode settings() { return feedback.settings(); }

    @PutMapping("/settings") @PreAuthorize("hasAuthority('closet:feedback:write')")
    public JsonNode putSettings(@Valid @RequestBody ClosetFeedbackRequests.Settings request) { return feedback.putSettings(request); }

    @PatchMapping("/{id}") @PreAuthorize("hasAuthority('closet:feedback:write')")
    public JsonNode edit(@PathVariable String id, @Valid @RequestBody ClosetFeedbackRequests.Edit request) { return feedback.edit(id, request); }

    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('closet:feedback:write')")
    public ResponseEntity<Void> delete(@PathVariable String id) { feedback.delete(id); return ResponseEntity.noContent().build(); }
}
