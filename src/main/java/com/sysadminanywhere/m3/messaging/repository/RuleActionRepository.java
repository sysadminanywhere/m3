package com.sysadminanywhere.m3.messaging.repository;

import com.sysadminanywhere.m3.messaging.domain.RuleAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RuleActionRepository extends JpaRepository<RuleAction, Long> {

    List<RuleAction> findByRuleId(Long ruleId);
}
