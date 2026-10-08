package com.sysadminanywhere.m3.community;

import com.sysadminanywhere.m3.extensions.ExecutionPolicy.PoolDemand;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CommunityExecutionPolicyTest {
    private final CommunityExecutionPolicy policy = new CommunityExecutionPolicy();
    private PoolDemand pool(Long id, String name, long backlog) {
        return new PoolDemand(id, name, 0, 8, 1, 8, true, 100, backlog);
    }
    @Test void fixedPolicyNeverEnablesPaidFeatures() {
        assertThat(policy.state().maxWorkers()).isEqualTo(1);
        assertThat(policy.state().autoScale()).isFalse();
        assertThat(policy.state().externalAuthentication()).isFalse();
        assertThatThrownBy(policy::requireExternalAuthentication).isInstanceOf(SecurityException.class);
        assertThat(policy.requested(pool(1L,"default",10000))).isEqualTo(1);
    }
    @Test void existingQueuesShareExactlyOneSlotAfterSwitchingFromScale() {
        var pools=List.of(pool(1L,"a",5),pool(2L,"b",9),pool(3L,"idle",0));
        var first=policy.targets(pools,0);
        var second=policy.targets(pools,1);
        assertThat(first.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(first).containsEntry("a",1).containsEntry("b",0).containsEntry("idle",0);
        assertThat(second).containsEntry("a",0).containsEntry("b",1);
    }
    @Test void rejectsNewPoolsAutoscalingAndHigherCapacity() {
        var pools=List.of(pool(1L,"default",0));
        assertThatThrownBy(() -> policy.validate(pools,null,1,1,1,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate(pools,1L,1,1,1,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate(pools,1L,2,1,2,false)).isInstanceOf(IllegalArgumentException.class);
        policy.validate(pools,1L,1,1,1,false);
    }
    @Test void aStoppedSinglePoolStaysStoppedEvenWithQueuedJobs() {
        var stopped=new PoolDemand(1L,"default",0,0,0,1,false,100,10);
        assertThat(policy.targets(List.of(stopped),0)).containsEntry("default",0);
    }
}
