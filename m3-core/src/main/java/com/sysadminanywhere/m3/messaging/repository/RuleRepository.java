package com.sysadminanywhere.m3.messaging.repository;

import com.sysadminanywhere.m3.messaging.domain.ChannelSettings;
import com.sysadminanywhere.m3.messaging.domain.Rule;
import com.sysadminanywhere.m3.messaging.domain.RuleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RuleRepository extends JpaRepository<Rule, Long> {
    boolean existsBySourceChannel_Id(Long id);
    long countByWorkerPool_Id(Long id);
    @org.springframework.data.jpa.repository.Modifying
    @Query(value="update rule set worker_pool_id=:id where worker_pool_id is null",nativeQuery=true)
    int assignUnassigned(@Param("id") Long id);

    List<Rule> findByRuleType(RuleType ruleType);

    List<Rule> findBySourceChannel(ChannelSettings sourceChannel);

    List<Rule> findBySourceChannelAndEnabled(ChannelSettings sourceChannel, Boolean enabled);

    @Query("SELECT r FROM Rule r WHERE r.sourceChannel = :sourceChannel AND r.enabled = true ORDER BY r.priority ASC")
    List<Rule> findEnabledBySourceChannelOrderByPriority(@Param("sourceChannel") ChannelSettings sourceChannel);

    @Query("SELECT r FROM Rule r WHERE r.enabled = true ORDER BY r.priority ASC")
    List<Rule> findAllEnabledOrderByPriority();

    List<Rule> findBySourceChannelId(Long channelId);

    @Query("SELECT r FROM Rule r WHERE r.sourceChannel.name = :channelName AND r.enabled = :enabled ORDER BY r.priority ASC")
    List<Rule> findBySourceChannelNameAndEnabled(@Param("channelName") String channelName,
                                                  @Param("enabled") Boolean enabled);

    @Query("SELECT r FROM Rule r WHERE r.sourceChannel.id = :channelId AND r.enabled = true ORDER BY r.priority ASC")
    List<Rule> findEnabledBySourceChannelIdOrderByPriority(@Param("channelId") Long channelId);
}
