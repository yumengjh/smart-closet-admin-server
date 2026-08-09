package com.yumg.starter.modules.security.api;

import com.yumg.starter.modules.security.api.dto.IpAccessRuleRequest;
import com.yumg.starter.modules.security.api.dto.IpAccessRuleResponse;
import com.yumg.starter.modules.security.application.IpAccessRuleService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController @RequestMapping("/api/v1/system/ip-access-rules")
@Tag(name = "IP 访问规则") @SecurityRequirement(name = "bearerAuth")
public class IpAccessRuleController {
 private final IpAccessRuleService rules; public IpAccessRuleController(IpAccessRuleService rules){this.rules=rules;}
 @GetMapping @PreAuthorize("hasAuthority('system:config:read')") public List<IpAccessRuleResponse> list(){return rules.list();}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('system:config:write')") public IpAccessRuleResponse create(@Valid @RequestBody IpAccessRuleRequest request){return rules.create(request);}
 @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAuthority('system:config:write')") public void delete(@PathVariable String id){rules.revoke(id);}
}
