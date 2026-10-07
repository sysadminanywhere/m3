package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@Profile("!worker")
public class DockerWorkerScaler {
    @org.springframework.beans.factory.annotation.Autowired private WorkerDrainService drains;
    private final String apiUrl;
    private final String image;
    private final String network;
    private final String filesVolume;
    private final int outboundMaxAttempts;
    private final String datasourceUrl;
    private final String datasourceUsername;
    private final String datasourcePassword;
    private final ObjectMapper mapper;
    private final org.springframework.core.env.Environment environment;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public DockerWorkerScaler(@Value("${m3.docker.api-url:}") String apiUrl,
                              @Value("${m3.worker.image:m3:local}") String image,
                              @Value("${m3.worker.network:}") String network,
                              @Value("${m3.worker.files-volume:}") String filesVolume,
                              @Value("${m3.outbound.max-attempts:20}") int outboundMaxAttempts,
                              @Value("${spring.datasource.url}") String datasourceUrl,
                              @Value("${spring.datasource.username}") String datasourceUsername,
                              @Value("${spring.datasource.password}") String datasourcePassword,
                              ObjectMapper mapper, org.springframework.core.env.Environment environment) {
        this.apiUrl = apiUrl == null ? "" : apiUrl.replaceAll("/+$", "");
        this.image = image;
        this.network = network;
        this.filesVolume = filesVolume;
        this.outboundMaxAttempts = outboundMaxAttempts;
        this.datasourceUrl = datasourceUrl;
        this.datasourceUsername = datasourceUsername;
        this.datasourcePassword = datasourcePassword;
        this.mapper = mapper;
        this.environment = environment;
    }

    public boolean isConfigured() { return !apiUrl.isBlank() && !network.isBlank(); }

    public int runningCount(String pool) throws Exception {
        if (!isConfigured()) return -1;
        return (int) list(pool).values().stream().filter(c -> "running".equalsIgnoreCase(c.state())).count();
    }

    public PoolLoad load(String pool) throws Exception {
        var containers = containerLoads(pool);
        return new PoolLoad(containers.stream().mapToDouble(ContainerLoad::cpuPercent).sum(),
                containers.stream().mapToDouble(ContainerLoad::memoryPercent).average().orElse(0));
    }
    public List<ContainerLoad> containerLoads(String pool) throws Exception {
        if (!isConfigured()) return List.of();
        var result = new ArrayList<ContainerLoad>();
        for (var container : list(pool).values()) {
            if (!"running".equalsIgnoreCase(container.state())) continue;
            JsonNode stats = mapper.readTree(request("GET", "/containers/" + container.id()
                    + "/stats?stream=false", null, Set.of(200)).body());
            JsonNode cpuStats = stats.path("cpu_stats"), previous = stats.path("precpu_stats");
            long cpuDelta = cpuStats.path("cpu_usage").path("total_usage").asLong()
                    - previous.path("cpu_usage").path("total_usage").asLong();
            long systemDelta = cpuStats.path("system_cpu_usage").asLong() - previous.path("system_cpu_usage").asLong();
            int cores = cpuStats.path("online_cpus").asInt(cpuStats.path("cpu_usage").path("percpu_usage").size());
            double cpu = cpuDelta > 0 && systemDelta > 0 ? (double) cpuDelta / systemDelta * cores * 100 : 0;
            JsonNode memory = stats.path("memory_stats");
            long cache = memory.path("stats").path("inactive_file").asLong(
                    memory.path("stats").path("total_inactive_file").asLong(memory.path("stats").path("cache").asLong()));
            long limit = memory.path("limit").asLong();
            double ram = limit > 0 ? (double) Math.max(0,memory.path("usage").asLong()-cache)/limit*100 : 0;
            result.add(new ContainerLoad(container.id(),container.name(),cpu,Math.clamp(ram,0,100)));
        }
        return result;
    }
    public record ContainerLoad(String id,String name,double cpuPercent,double memoryPercent) { }

    public synchronized void removePool(String pool) {
        if (!isConfigured()) return;
        try {
            for (var container : list(pool).values()) remove(container);
        }
        catch (Exception e) { throw new IllegalStateException("Could not remove Docker workers for pool '" + pool + "'", e); }
    }

    public synchronized void reconcile(RuleWorkerPool pool, int desiredReplicas) {
        if (!isConfigured()) return;
        try {
            String imageId = mapper.readTree(request("GET", "/images/" + URLEncoder.encode(image, StandardCharsets.UTF_8)
                    + "/json", null, Set.of(200)).body()).path("Id").asText();
            if (imageId.isBlank()) throw new IllegalStateException("Worker image ID is unavailable");
            Map<String, ContainerInfo> current = list(pool.getName());
            for (var container : current.values()) {
                int index = indexOf(pool.getName(), container.name());
                if (index > desiredReplicas) remove(container);
            }
            current = list(pool.getName());
            for (int index = 1; index <= desiredReplicas; index++) {
                String name = containerName(pool.getName(), index);
                ContainerInfo found = current.get(name);
                if (found == null) {
                    drains.resume(name);
                    String id = create(pool, name, index, imageId);
                    request("POST", "/containers/" + id + "/start", null, Set.of(204));
                } else if (!imageId.equals(found.imageId()) || !revision(pool,index,imageId).equals(found.revision())) {
                    // Replace one existing worker per reconciliation, pinning creation to the inspected image.
                    if(!remove(found)) return;
                    drains.resume(name);
                    String id = create(pool, name, index, imageId);
                    request("POST", "/containers/" + id + "/start", null, Set.of(204));
                    return;
                } else if (!"running".equalsIgnoreCase(found.state())) {
                    drains.resume(name);
                    request("POST", "/containers/" + found.id() + "/start", null, Set.of(204, 304));
                } else drains.resume(name);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not reconcile Docker workers for pool '" + pool.getName() + "'", e);
        }
    }

    public synchronized void removeOrphanPools(java.util.function.Predicate<String> poolExists) {
        if (!isConfigured()) return;
        try {
            for (var container : list(null).values())
                if (!poolExists.test(container.pool())) remove(container);
        } catch (Exception error) { throw new IllegalStateException("Could not remove orphan workers", error); }
    }

    private Map<String, ContainerInfo> list(String pool) throws Exception {
        var labels = new ArrayList<String>();
        labels.add("m3.worker.managed=true");
        if (pool != null) labels.add("m3.worker.pool=" + pool);
        String filter = mapper.writeValueAsString(Map.of("label", labels));
        String path = "/containers/json?all=1&filters=" + URLEncoder.encode(filter, StandardCharsets.UTF_8);
        JsonNode rows = mapper.readTree(request("GET", path, null, Set.of(200)).body());
        Map<String, ContainerInfo> result = new HashMap<>();
        for (JsonNode row : rows) {
            String id = row.path("Id").asText();
            String state = row.path("State").asText();
            JsonNode names = row.path("Names");
            if (names.isArray()) for (JsonNode value : names) {
                String name = value.asText().replaceFirst("^/", "");
                result.put(name, new ContainerInfo(id, name, state, row.path("ImageID").asText(), row.path("Labels").path("m3.worker.pool").asText(),row.path("Labels").path("m3.worker.config").asText()));
            }
        }
        return result;
    }

    private List<String> workerEnvironment(RuleWorkerPool pool,int index) {
        var env = new ArrayList<>(List.of(
                "SPRING_PROFILES_ACTIVE=worker",
                "SPRING_DATASOURCE_URL=" + datasourceUrl,
                "SPRING_DATASOURCE_USERNAME=" + datasourceUsername,
                "SPRING_DATASOURCE_PASSWORD=" + datasourcePassword,
                "SPRING_JPA_HIBERNATE_DDL_AUTO=validate",
                "SPRING_LIQUIBASE_ENABLED=false",
                "SPRING_JPA_SHOW_SQL=false",
                "M3_SECRET_KEY=" + environment.getProperty("m3.secret-key"),
                "M3_OUTBOUND_MAX_ATTEMPTS=" + outboundMaxAttempts,
                "M3_WORKER_POOL=" + pool.getName(),
                "M3_WORKER_INDEX=" + index,
                "HOSTNAME=" + containerName(pool.getName(),index),
                "M3_LICENSE_PUBLIC_KEY=" + environment.getProperty("m3.license.public-key",""),
                "M3_STORAGE_MODE=KEEP"
        ));
        for (String key : List.of("host","port","username","password","virtual-host","exchange","queue")) {
            String value=environment.getProperty("m3.broker.rabbit."+key);
            if (value!=null) env.add("M3_BROKER_RABBIT_"+key.toUpperCase(Locale.ROOT).replace('-','_')+"="+value);
        }
        return env;
    }
    private String revision(RuleWorkerPool pool,int index,String imageId) throws Exception {
        return SourceDeliveryService.digest(mapper.writeValueAsBytes(List.of(imageId,network,filesVolume,workerEnvironment(pool,index))));
    }

    private String create(RuleWorkerPool pool, String name, int index, String imageId) throws Exception {
        List<String> env = workerEnvironment(pool,index);
        Map<String, Object> hostConfig = new HashMap<>();
        hostConfig.put("NetworkMode", network);
        hostConfig.put("RestartPolicy", Map.of("Name", "unless-stopped"));
        if (!filesVolume.isBlank()) hostConfig.put("Mounts", List.of(
                Map.of("Type", "volume", "Source", filesVolume, "Target", "/data")));
        Map<String, Object> body = Map.of(
                "Image", imageId,
                "Cmd", List.of("--spring.profiles.active=worker"),
                "Env", env,
                "Labels", Map.of("m3.worker.pool", pool.getName(), "m3.worker.managed", "true", "m3.worker.config", revision(pool,index,imageId)),
                "HostConfig", hostConfig
        );
        String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
        JsonNode created = mapper.readTree(request("POST", "/containers/create?name=" + encodedName,
                mapper.writeValueAsString(body), Set.of(201)).body());
        return created.path("Id").asText();
    }

    private boolean remove(ContainerInfo container) throws Exception {
        if(!drains.request(container.name())) return false;
        if ("running".equalsIgnoreCase(container.state())) {
            request("POST", "/containers/" + container.id() + "/stop?t=45", null, Set.of(204, 304));
        }
        request("DELETE", "/containers/" + container.id() + "?force=true&v=false", null, Set.of(204));
        return true;
    }

    private HttpResponse<String> request(String method, String path, String body, Set<Integer> accepted) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(apiUrl + path)).timeout(Duration.ofSeconds(path.contains("/stop?") ? 60 : 20));
        if (body != null) builder.header("Content-Type", "application/json");
        HttpRequest request = switch (method) {
            case "GET" -> builder.GET().build();
            case "POST" -> builder.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            case "DELETE" -> builder.DELETE().build();
            default -> throw new IllegalArgumentException("Unsupported method " + method);
        };
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (!accepted.contains(response.statusCode())) {
            throw new IllegalStateException("Docker API returned HTTP " + response.statusCode());
        }
        return response;
    }

    private int indexOf(String pool, String name) {
        try { return Integer.parseInt(name.substring(containerName(pool, 1).length() - 1)); }
        catch (RuntimeException ignored) { return Integer.MAX_VALUE; }
    }

    private String containerName(String pool, int index) { return "m3-worker-" + pool + "-" + index; }
    public record PoolLoad(double cpuPercent, double memoryPercent) {}
    private record ContainerInfo(String id, String name, String state, String imageId, String pool, String revision) {}
}
