package com.sysadminanywhere.m3.extensions;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared contract; contains no paid feature implementation or license verifier. */
public interface ExecutionPolicy {
    record State(String tier, int maxWorkers, boolean autoScale, String customer,
                 Instant expiresAt, String condition, boolean externalAuthentication) {
        public static State community(String condition) {
            return new State("Community", 1, false, "", null, condition, false);
        }
    }
    record PoolDemand(Long id, String name, int priority, int desired, int min, int max,
                      boolean autoScale, int pendingJobsPerWorker, long backlog) { }

    /** Additional runtime settings for managed workers, supplied by the selected policy. */
    default Map<String, String> workerEnvironment() { return Map.of(); }

    State state();
    Map<String, Integer> targets(List<PoolDemand> pools, long epochMinute);
    int requested(PoolDemand pool);
    void validate(List<PoolDemand> pools, Long replacingId, int desired, int min, int max, boolean autoScale);

    /** An external connector must call this for login and continued session access. */
    default void requireExternalAuthentication() {
        throw new SecurityException("External authentication is unavailable in this distribution");
    }
}
