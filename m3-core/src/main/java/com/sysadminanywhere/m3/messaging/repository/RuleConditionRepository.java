package com.sysadminanywhere.m3.messaging.repository;

import com.sysadminanywhere.m3.messaging.domain.RuleCondition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RuleConditionRepository extends JpaRepository<RuleCondition, Long> {

    List<RuleCondition> findByRuleId(Long ruleId);
}
