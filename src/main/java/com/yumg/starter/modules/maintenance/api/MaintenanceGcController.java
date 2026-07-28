package com.yumg.starter.modules.maintenance.api;
import com.yumg.starter.common.api.PageResponse;
import com.yumg.starter.modules.maintenance.api.dto.GcPolicyUpdateRequest;
import com.yumg.starter.modules.maintenance.api.dto.GcRunRequest;
import com.yumg.starter.modules.maintenance.api.dto.GcScheduleUpdateRequest;
import com.yumg.starter.modules.maintenance.application.GcExecutionService;
import com.yumg.starter.modules.maintenance.application.GcPolicyContent;
import com.yumg.starter.modules.maintenance.application.GcPolicyService;
import com.yumg.starter.modules.maintenance.application.GcRunContent;
import com.yumg.starter.modules.maintenance.application.GcScheduleContent;
import com.yumg.starter.modules.maintenance.application.GcScheduler;
import com.yumg.starter.modules.maintenance.application.GcSchedulerStatus;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequestMapping("/api/v1/system/gc")
public class MaintenanceGcController {
 private final GcPolicyService policies; private final GcExecutionService execution; private final RuntimeSettingService settings; private final GcScheduler scheduler;
 public MaintenanceGcController(GcPolicyService policies, GcExecutionService execution, RuntimeSettingService settings, GcScheduler scheduler){this.policies=policies;this.execution=execution;this.settings=settings;this.scheduler=scheduler;}
 @GetMapping("/policies") @PreAuthorize("hasAuthority('system:gc:read')") public List<GcPolicyContent> policies(){return policies.list();}
 @PutMapping("/policies/{code}") @PreAuthorize("hasAuthority('system:gc:write')") public GcPolicyContent update(@PathVariable String code,@Valid @RequestBody GcPolicyUpdateRequest request){return policies.update(code,request.enabled(),request.automaticEnabled(),request.retentionDays(),request.batchSize());}
 @GetMapping("/schedule") @PreAuthorize("hasAuthority('system:gc:read')") public GcScheduleContent schedule(){GcSchedulerStatus status=scheduler.status();return new GcScheduleContent(settings.enabled("maintenance.gc.schedule.enabled"),settings.integer("maintenance.gc.interval-minutes"),status.lastAttemptedAt(),status.lastSucceededAt(),status.lastFailedAt(),status.consecutiveFailures(),status.lastFailureMessage(),status.nextEligibleAt());}
 @PutMapping("/schedule") @PreAuthorize("hasAuthority('system:gc:write')") public GcScheduleContent updateSchedule(@Valid @RequestBody GcScheduleUpdateRequest request){settings.updateAll(Map.of("maintenance.gc.schedule.enabled",request.automaticEnabled().toString(),"maintenance.gc.interval-minutes",request.intervalMinutes().toString()));return schedule();}
 @PostMapping("/runs") @PreAuthorize("hasAuthority('system:gc:write')") public GcRunContent run(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody GcRunRequest request){return execution.run("MANUAL",request.dryRun(),request.resourceCodes(),jwt.getSubject());}
 @GetMapping("/runs") @PreAuthorize("hasAuthority('system:gc:read')") public PageResponse<GcRunContent> history(@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return execution.history(page,size);}
}
