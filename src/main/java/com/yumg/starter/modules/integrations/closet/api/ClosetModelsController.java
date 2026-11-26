package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.modules.integrations.closet.application.ClosetModelsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/closet/models")
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetModelsController {
    private final ClosetModelsService models;
    public ClosetModelsController(ClosetModelsService models) { this.models = models; }

    @GetMapping @PreAuthorize("hasAuthority('closet:models:read')")
    public JsonNode overview() { return models.overview(); }

    @GetMapping("/usage") @PreAuthorize("hasAuthority('closet:models:read')")
    public JsonNode usage(@RequestParam MultiValueMap<String, String> query) { return models.usage(query); }

    @PostMapping("/providers") @PreAuthorize("hasAuthority('closet:models:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode createProvider(@RequestBody JsonNode body) { return models.createProvider(body); }
    @PatchMapping("/providers/{id}") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode updateProvider(@PathVariable String id, @RequestBody JsonNode body) { return models.updateProvider(id, body); }
    @DeleteMapping("/providers/{id}") @PreAuthorize("hasAuthority('closet:models:write')")
    public ResponseEntity<Void> deleteProvider(@PathVariable String id) { models.deleteProvider(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/providers/{id}/probe") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode probe(@PathVariable String id) { return models.probe(id); }

    @PostMapping("/entries") @PreAuthorize("hasAuthority('closet:models:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode createEntry(@RequestBody JsonNode body) { return models.createEntry(body); }
    @PatchMapping("/entries/{id}") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode updateEntry(@PathVariable String id, @RequestBody JsonNode body) { return models.updateEntry(id, body); }
    @PutMapping("/entries/{id}/references") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode setReferences(@PathVariable String id, @RequestBody JsonNode body) { return models.setReferences(id, body); }
    @DeleteMapping("/entries/{id}") @PreAuthorize("hasAuthority('closet:models:write')")
    public ResponseEntity<Void> deleteEntry(@PathVariable String id) { models.deleteEntry(id); return ResponseEntity.noContent().build(); }
    @PostMapping("/entries/{id}/resume") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode resume(@PathVariable String id) { return models.resume(id); }
    @PostMapping("/entries/{id}/test") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode test(@PathVariable String id) { return models.test(id); }

    @PutMapping("/routing/{capability}") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode route(@PathVariable String capability, @RequestBody JsonNode body) { return models.route(capability, body); }
    @DeleteMapping("/routing/{capability}") @PreAuthorize("hasAuthority('closet:models:write')")
    public JsonNode resetRoute(@PathVariable String capability) { return models.resetRoute(capability); }
}
