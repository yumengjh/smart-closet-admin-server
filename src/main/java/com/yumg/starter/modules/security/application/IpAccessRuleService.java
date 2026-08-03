package com.yumg.starter.modules.security.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.IpAccessRule;
import com.yumg.starter.modules.security.api.dto.IpAccessRuleRequest;
import com.yumg.starter.modules.security.api.dto.IpAccessRuleResponse;
import com.yumg.starter.modules.security.infrastructure.IpAccessRuleRepository;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCacheProvider;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCacheRevisionService;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCacheStatus;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class IpAccessRuleService implements RuntimeCacheProvider {
 public static final String CACHE_CODE="IP_ACCESS_RULES";
 private static final String API_SCOPE="API";
 private final IpAccessRuleRepository rules; private final AuditService audit; private final RuntimeCacheRevisionService revisions;
 private volatile Snapshot snapshot=Snapshot.empty();
 private volatile long snapshotRevision; private volatile long databaseRevision;
 private volatile Instant loadedAt; private volatile Instant lastCheckedAt; private volatile Instant lastReloadedAt;
 private volatile Instant lastFailedAt; private volatile String lastFailureMessage; private long localRevision;
 public enum Decision { ALLOW, DENY }
 public IpAccessRuleService(IpAccessRuleRepository rules, AuditService audit){this(rules,audit,null);}
 @Autowired public IpAccessRuleService(IpAccessRuleRepository rules, AuditService audit,RuntimeCacheRevisionService revisions){this.rules=rules;this.audit=audit;this.revisions=revisions;}
 @PostConstruct void initialize(){refreshSnapshot(ensureRevision());}
 void refreshSnapshot(){refreshSnapshot(currentRevision());}
 @Transactional(readOnly=true) public List<IpAccessRuleResponse> list(){return rules.findAllByScope(API_SCOPE).stream().map(IpAccessRuleResponse::from).toList();}
 @Transactional public IpAccessRuleResponse create(IpAccessRuleRequest request){
  if(!IpNetworkMatcher.matches(request.network(), networkAddress(request.network()))) throw ApiException.notFound();
  Instant expiresAt=request.expiresAt()==null?null:Instant.ofEpochMilli(request.expiresAt());
  IpAccessRule rule=rules.save(new IpAccessRule(request.type(),request.network(),API_SCOPE,expiresAt,request.reason().trim()));
  long revision=incrementRevision(); refreshSnapshotAfterCommit(revision); audit.event("IP_ACCESS_RULE_CREATED","IpAccessRule",rule.getId(),"SUCCESS","{\"type\":\""+request.type()+"\"}"); return IpAccessRuleResponse.from(rule);
 }
 @Transactional public void delete(String id){rules.delete(rules.findById(id).orElseThrow(ApiException::notFound));long revision=incrementRevision();refreshSnapshotAfterCommit(revision);audit.event("IP_ACCESS_RULE_DELETED","IpAccessRule",id);}
 public Decision decision(String ip,Instant now){return snapshot.decision(ip,now);}
 @Override public String code(){return CACHE_CODE;}
 @Override public String displayName(){return "IP 访问规则";}
 @Override public int order(){return 100;}
 @Override public RuntimeCacheStatus status(){return new RuntimeCacheStatus(code(),displayName(),snapshotRevision,databaseRevision,snapshotRevision==databaseRevision,loadedAt,lastCheckedAt,lastReloadedAt,lastFailedAt,lastFailureMessage,snapshot.allow().size()+snapshot.deny().size());}
 @Override @Transactional(readOnly=true) public synchronized RuntimeCacheStatus forceRefresh(){lastCheckedAt=Instant.now();try{refreshSnapshot(currentRevision());return status();}catch(RuntimeException exception){lastFailedAt=Instant.now();lastFailureMessage=failureMessage(exception);throw exception;}}
 @Override @Transactional(readOnly=true) public boolean refreshIfChanged(){lastCheckedAt=Instant.now();long current=currentRevision();databaseRevision=current;if(current==snapshotRevision)return false;forceRefresh();return true;}
 private record Rule(String network,Instant expiresAt) { boolean activeAt(Instant now){return expiresAt==null||expiresAt.isAfter(now);} }
 private record Snapshot(List<Rule> allow,List<Rule> deny){
  static Snapshot empty(){return new Snapshot(List.of(),List.of());}
  static Snapshot from(List<IpAccessRule> rules){return new Snapshot(rules.stream().filter(rule->rule.getType()==IpAccessRule.Type.ALLOW).map(rule->new Rule(rule.getNetwork(),rule.getExpiresAt())).toList(),rules.stream().filter(rule->rule.getType()==IpAccessRule.Type.DENY).map(rule->new Rule(rule.getNetwork(),rule.getExpiresAt())).toList());}
  Decision decision(String ip,Instant now){if(deny.stream().anyMatch(rule->rule.activeAt(now)&&IpNetworkMatcher.matches(rule.network(),ip)))return Decision.DENY;boolean hasAllow=allow.stream().anyMatch(rule->rule.activeAt(now));return !hasAllow||allow.stream().anyMatch(rule->rule.activeAt(now)&&IpNetworkMatcher.matches(rule.network(),ip))?Decision.ALLOW:Decision.DENY;}
 }
 private void refreshSnapshotAfterCommit(long revision){
  if(!TransactionSynchronizationManager.isSynchronizationActive()){refreshSnapshot(revision);return;}
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCommit(){refreshSnapshot(revision);}
  });
 }
 private synchronized void refreshSnapshot(long revision){if(revision<snapshotRevision)return;snapshot=Snapshot.from(rules.findAllByScope(API_SCOPE));Instant now=Instant.now();snapshotRevision=revision;databaseRevision=revision;loadedAt=loadedAt==null?now:loadedAt;lastReloadedAt=now;lastFailureMessage=null;}
 private long ensureRevision(){return revisions==null?localRevision:revisions.ensure(CACHE_CODE);}
 private long currentRevision(){return revisions==null?localRevision:revisions.current(CACHE_CODE);}
 private long incrementRevision(){return revisions==null?++localRevision:revisions.increment(CACHE_CODE);}
 private String failureMessage(RuntimeException exception){String message=exception.getMessage();if(message==null||message.isBlank())message=exception.getClass().getSimpleName();return message.length()<=500?message:message.substring(0,500);}
 private String networkAddress(String network){return network.split("/",2)[0];}
}
