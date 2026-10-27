package com.sysadminanywhere.m3.messaging.ui;

import com.sysadminanywhere.m3.messaging.domain.*;
import com.sysadminanywhere.m3.messaging.service.SystemOverviewService.PoolStatus;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RuleReadinessTest {
    @Test void reportsConfigurationBeforeConsultingWorkers() {
        var rule = mock(Rule.class);
        var pools = mock(java.util.function.Supplier.class);
        when(rule.getEnabled()).thenReturn(false);
        assertThat(RuleDetailView.readinessMessage(rule, pools, List::of)).contains("disabled");
        when(rule.getEnabled()).thenReturn(true);
        when(rule.getRuleType()).thenReturn(RuleType.INBOUND);
        assertThat(RuleDetailView.readinessMessage(rule, pools, List::of)).contains("source channel");
        var source = mock(ChannelSettings.class);
        when(source.getEnabled()).thenReturn(true); when(rule.getSourceChannel()).thenReturn(source);
        assertThat(RuleDetailView.readinessMessage(rule, pools, List::of)).contains("no worker pool");
        verifyNoInteractions(pools);
    }

    @Test void checksOnlyTheSavedRulesPool() {
        var rule = mock(Rule.class); var pool = mock(RuleWorkerPool.class);
        when(rule.getEnabled()).thenReturn(true); when(rule.getRuleType()).thenReturn(RuleType.OUTBOUND);
        when(rule.getDestinationChannelName()).thenReturn("destination");
        var destination = mock(ChannelSettings.class);
        when(destination.getName()).thenReturn("destination"); when(destination.getEnabled()).thenReturn(true);
        when(rule.getWorkerPool()).thenReturn(pool); when(pool.getId()).thenReturn(42L);
        var other = new PoolStatus(43,"other",0,0,0,1,1,null);
        var offline = new PoolStatus(42,"target",0,0,0,0,1,null);
        var online = new PoolStatus(42,"target",0,0,0,1,1,null);
        assertThat(RuleDetailView.readinessMessage(rule, () -> List.of(other,offline), () -> List.of(destination))).contains("no recent worker heartbeat");
        assertThat(RuleDetailView.readinessMessage(rule, () -> List.of(other,online), () -> List.of(destination))).contains("heartbeat is available");
        when(destination.getEnabled()).thenReturn(false);
        assertThat(RuleDetailView.readinessMessage(rule, () -> List.of(other,online), () -> List.of(destination))).contains("destination channel");
    }
}
