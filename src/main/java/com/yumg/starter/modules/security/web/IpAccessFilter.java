package com.yumg.starter.modules.security.web;
import com.yumg.starter.common.api.ApiErrorCode;
import com.yumg.starter.common.api.ApiErrorWriter;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import com.yumg.starter.modules.security.application.IpNetworkMatcher;
import com.yumg.starter.common.web.ClientIpResolver;
import jakarta.servlet.*; import jakarta.servlet.http.*; import java.io.IOException; import java.util.*;
import org.springframework.http.HttpMethod; import org.springframework.stereotype.Component; import org.springframework.web.filter.OncePerRequestFilter;
@Component public class IpAccessFilter extends OncePerRequestFilter {
 private final RuntimeSettingService settings; private final ApiErrorWriter errors; private final ClientIpResolver clientIp; private final com.yumg.starter.modules.security.application.IpAccessRuleService rules;
 public IpAccessFilter(RuntimeSettingService settings, ApiErrorWriter errors, ClientIpResolver clientIp, com.yumg.starter.modules.security.application.IpAccessRuleService rules){this.settings=settings;this.errors=errors;this.clientIp=clientIp;this.rules=rules;}
 @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getRequestURI().startsWith("/api/")||HttpMethod.OPTIONS.matches(r.getMethod());}
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain c)throws ServletException,IOException{
  if(isRuntimeRecoveryEndpoint(r.getRequestURI())){c.doFilter(r,s);return;}
  String ip=clientIp.resolve(r); Set<String> allow=list("security.ip.allow-list"), deny=list("security.ip.deny-list");
  if((!allow.isEmpty()&&!matches(allow,ip))||matches(deny,ip)||rules.decision(ip,java.time.Instant.now())==com.yumg.starter.modules.security.application.IpAccessRuleService.Decision.DENY){errors.write(s,ApiErrorCode.IP_ACCESS_DENIED);return;} c.doFilter(r,s);
 }
 private Set<String> list(String k){return Arrays.stream(settings.string(k).split(",")).map(String::trim).filter(v->!v.isEmpty()).collect(java.util.stream.Collectors.toSet());}
 private boolean matches(Set<String> networks,String ip){return networks.stream().anyMatch(network -> IpNetworkMatcher.matches(network,ip));}
 private boolean isRuntimeRecoveryEndpoint(String path){return path.startsWith("/api/v1/system/runtime-config")||path.startsWith("/api/v1/system/runtime-caches");}
}
