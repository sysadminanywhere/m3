package com.sysadminanywhere.m3.messaging.repository;

import com.sysadminanywhere.m3.messaging.domain.RuleAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RuleActionRepository extends JpaRepository<RuleAction, Long> {
    boolean existsByDestinationChannel_Id(Long id);

    List<RuleAction> findByRuleId(Long ruleId);
    List<RuleAction> findByTargetChannel(String targetChannel);
}
