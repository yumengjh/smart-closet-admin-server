package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.modules.integrations.closet.api.dto.ClosetStyleTermBatchRequest;
import com.yumg.starter.modules.integrations.closet.api.dto.ClosetStyleTermReviewRequest;
import com.yumg.starter.modules.integrations.closet.application.ClosetStyleTermService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/closet/style-terms/pending")
public class ClosetStyleTermController {
    private final ClosetStyleTermService terms;
    public ClosetStyleTermController(ClosetStyleTermService terms) { this.terms = terms; }

    @GetMapping @PreAuthorize("hasAuthority('closet:styleterms:read')")
    public JsonNode pending(@RequestParam MultiValueMap<String, String> query) { return terms.pending(query); }
    @PostMapping("/approve") @PreAuthorize("hasAuthority('closet:styleterms:write')")
    public JsonNode approve(@Valid @RequestBody ClosetStyleTermReviewRequest request) { return terms.approve(request); }
    @PostMapping("/reject") @PreAuthorize("hasAuthority('closet:styleterms:write')")
    public JsonNode reject(@Valid @RequestBody ClosetStyleTermReviewRequest request) { return terms.reject(request); }
    @PostMapping("/batch") @PreAuthorize("hasAuthority('closet:styleterms:write')")
    public JsonNode batch(@Valid @RequestBody ClosetStyleTermBatchRequest request) { return terms.batch(request); }
}
