package com.yumg.starter.modules.security.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.IpAccessRule;
import com.yumg.starter.modules.security.api.dto.IpAccessRuleRequest;
import com.yumg.starter.modules.security.api.dto.IpAccessRuleResponse;
import com.yumg.starter.modules.security.infrastructure.IpAccessRuleRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class IpAccessRuleService {
 private static final String API_SCOPE="API";
 private final IpAccessRuleRepository rules; private final AuditService audit;
 private volatile Snapshot snapshot=Snapshot.empty();
 public enum Decision { ALLOW, DENY }
 public IpAccessRuleService(IpAccessRuleRepository rules, AuditService audit){this.rules=rules;this.audit=audit;}
 @PostConstruct void refreshSnapshot(){snapshot=Snapshot.from(rules.findAllByScope(API_SCOPE));}
 @Transactional(readOnly=true) public List<IpAccessRuleResponse> list(){return rules.findAllByScope(API_SCOPE).stream().map(IpAccessRuleResponse::from).toList();}
 @Transactional public IpAccessRuleResponse create(IpAccessRuleRequest request){
  if(!IpNetworkMatcher.matches(request.network(), networkAddress(request.network()))) throw ApiException.notFound();
  Instant expiresAt=request.expiresAt()==null?null:Instant.ofEpochMilli(request.expiresAt());
  IpAccessRule rule=rules.save(new IpAccessRule(request.type(),request.network(),API_SCOPE,expiresAt,request.reason().trim()));
  refreshSnapshotAfterCommit(); audit.event("IP_ACCESS_RULE_CREATED","IpAccessRule",rule.getId(),"SUCCESS","{\"type\":\""+request.type()+"\"}"); return IpAccessRuleResponse.from(rule);
 }
 @Transactional public void delete(String id){rules.delete(rules.findById(id).orElseThrow(ApiException::notFound));refreshSnapshotAfterCommit();audit.event("IP_ACCESS_RULE_DELETED","IpAccessRule",id);}
 public Decision decision(String ip,Instant now){return snapshot.decision(ip,now);}
 private record Rule(String network,Instant expiresAt) { boolean activeAt(Instant now){return expiresAt==null||expiresAt.isAfter(now);} }
 private record Snapshot(List<Rule> allow,List<Rule> deny){
  static Snapshot empty(){return new Snapshot(List.of(),List.of());}
  static Snapshot from(List<IpAccessRule> rules){return new Snapshot(rules.stream().filter(rule->rule.getType()==IpAccessRule.Type.ALLOW).map(rule->new Rule(rule.getNetwork(),rule.getExpiresAt())).toList(),rules.stream().filter(rule->rule.getType()==IpAccessRule.Type.DENY).map(rule->new Rule(rule.getNetwork(),rule.getExpiresAt())).toList());}
  Decision decision(String ip,Instant now){if(deny.stream().anyMatch(rule->rule.activeAt(now)&&IpNetworkMatcher.matches(rule.network(),ip)))return Decision.DENY;boolean hasAllow=allow.stream().anyMatch(rule->rule.activeAt(now));return !hasAllow||allow.stream().anyMatch(rule->rule.activeAt(now)&&IpNetworkMatcher.matches(rule.network(),ip))?Decision.ALLOW:Decision.DENY;}
 }
 private void refreshSnapshotAfterCommit(){
  if(!TransactionSynchronizationManager.isSynchronizationActive()){refreshSnapshot();return;}
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCommit(){refreshSnapshot();}
  });
 }
 private String networkAddress(String network){return network.split("/",2)[0];}
}
