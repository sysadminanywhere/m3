package com.sysadminanywhere.m3.community;

import com.sysadminanywhere.m3.extensions.ExecutionPolicy;
import org.springframework.stereotype.Service;
import java.util.*;

/** Fixed Community implementation. Does not read or accept signed entitlements. */
@Service
public class CommunityExecutionPolicy implements ExecutionPolicy {
    @Override public State state() { return State.community("COMMUNITY"); }
    @Override public int requested(PoolDemand pool) { return Math.min(1, pool.desired()); }

    @Override public Map<String, Integer> targets(List<PoolDemand> pools, long epochMinute) {
        var result = new LinkedHashMap<String, Integer>();
        var sorted = pools.stream().sorted(Comparator.comparing(PoolDemand::name)).toList();
        sorted.forEach(pool -> result.put(pool.name(), 0));
        if (sorted.size() == 1) {
            var pool = sorted.getFirst();
            result.put(pool.name(), requested(pool));
            return result;
        }
        // Retain access to existing queues after switching a former Scale installation.
        var busy = sorted.stream().filter(pool -> pool.backlog() > 0).toList();
        var candidates = busy.isEmpty() ? sorted.stream().filter(pool -> pool.desired() > 0).toList() : busy;
        if (!candidates.isEmpty())
            result.put(candidates.get((int) Math.floorMod(epochMinute, candidates.size())).name(), 1);
        return result;
    }

    @Override public void validate(List<PoolDemand> pools, Long replacingId, int desired, int min, int max, boolean autoScale) {
        if (replacingId == null && !pools.isEmpty())
            throw new IllegalArgumentException("Additional worker pools require the full distribution and Scale");
        if (autoScale) throw new IllegalArgumentException("Autoscaling requires the full distribution and Scale");
        if (desired > 1 || min > 1 || max > 1)
            throw new IllegalArgumentException("Community permits one worker slot");
    }
}
