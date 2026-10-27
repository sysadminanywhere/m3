package com.sysadminanywhere.m3.messaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sysadminanywhere.m3.messaging.domain.RuleWorkerPool;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DockerWorkerScalerIsolationTest {
    private static final String OWNER="11111111-1111-1111-1111-111111111111";
    private HttpServer server;
    private final List<String> requests=new ArrayList<>();
    private boolean legacy;
    private String legacyNetwork="test-net";
    private boolean createMode;
    private String createdBody;
    private DockerWorkerScaler scaler;

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            String path=exchange.getRequestURI().getPath();
            String query=URLDecoder.decode(Objects.toString(exchange.getRequestURI().getRawQuery(),""),StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod()+" "+path+"?"+query);
            String body="{}"; int status=200;
            if(path.equals("/containers/json")) {
                if (legacy && !query.contains("m3.worker.installation=")) body="[{\"Id\":\"legacy\",\"Labels\":{\"m3.worker.managed\":\"true\"}}]";
                else if(createMode) body="[]";
                else if(query.contains("m3.worker.installation="+OWNER)) body="[{\"Id\":\"own\",\"State\":\"running\",\"Names\":[\"/owned\"],\"Labels\":{\"m3.worker.pool\":\"default\"}}]";
                else body="[{\"Id\":\"foreign\",\"State\":\"running\",\"Names\":[\"/foreign\"],\"Labels\":{\"m3.worker.pool\":\"default\"}}]";
            } else if(path.equals("/containers/legacy/json")) body="{\"HostConfig\":{\"NetworkMode\":\""+legacyNetwork+"\"}}";
            else if(path.startsWith("/images/")) body="{\"Id\":\"sha256:test\"}";
            else if(path.equals("/containers/create")) { createdBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); body="{\"Id\":\"new\"}"; status=201; }
            else if(exchange.getRequestMethod().equals("POST") || exchange.getRequestMethod().equals("DELETE")) status=204;
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,status==204?-1:bytes.length);
            if(status!=204) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        scaler=new DockerWorkerScaler("http://127.0.0.1:"+server.getAddress().getPort(),"image","test-net","volume",20,
                "jdbc:postgresql://db/m3","user","password",new ObjectMapper(),new MockEnvironment());
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(String.class))).thenReturn(OWNER);
        var drains=mock(WorkerDrainService.class); when(drains.request(anyString())).thenReturn(true);
        ReflectionTestUtils.setField(scaler,"jdbc",jdbc); ReflectionTestUtils.setField(scaler,"drains",drains);
        var policy=mock(com.sysadminanywhere.m3.extensions.ExecutionPolicy.class);
        when(policy.workerEnvironment()).thenReturn(Map.of()); ReflectionTestUtils.setField(scaler,"policy",policy);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void orphanCleanupFiltersByInstallationBeforeStoppingContainers() {
        scaler.removeOrphanPools(pool -> false);
        assertThat(requests).anyMatch(request -> request.contains("m3.worker.installation="+OWNER));
        assertThat(requests).anyMatch(request -> request.contains("m3.worker.scoped=true"));
        assertThat(requests).anyMatch(request -> request.startsWith("DELETE /containers/own?"));
        assertThat(requests).noneMatch(request -> request.contains("/containers/foreign"));
    }

    @Test void legacyWorkerOnTheSameNetworkBlocksReconciliationWithoutMutation() throws Exception {
        legacy=true;
        assertThat(scaler.hasLegacyWorkersOnNetwork()).isTrue();
        var capacity=new WorkerPoolCapacityService(mock(com.sysadminanywhere.m3.messaging.repository.RuleWorkerPoolRepository.class),
                mock(com.sysadminanywhere.m3.messaging.repository.RuleExecutionJobRepository.class),scaler);
        assertThat(capacity.status("default")).isEqualTo("Legacy workers require removal before reconciliation");
        assertThatThrownBy(() -> scaler.reconcile(new RuleWorkerPool("default",1,1,1),1))
                .hasRootCauseMessage("Legacy managed workers exist on this network; stop and remove them before upgrading");
        assertThat(requests).noneMatch(request -> request.startsWith("POST ") || request.startsWith("DELETE "));
    }
    @Test void newWorkersCarryInstallationIdentityInLabelAndName() throws Exception {
        createMode=true;
        scaler.reconcile(new RuleWorkerPool("default",1,1,1),1);
        var body=new ObjectMapper().readTree(createdBody);
        assertThat(body.path("Labels").path("m3.worker.installation").asText()).isEqualTo(OWNER);
        assertThat(body.path("Labels").path("m3.worker.scoped").asText()).isEqualTo("true");
        assertThat(body.path("Labels").has("m3.worker.managed")).isFalse();
        assertThat(requests).anyMatch(request -> request.startsWith("POST /containers/create?")
                && request.contains("m3-11111111111111111111111111111111-worker-default-1"));
        assertThat(requests).anyMatch(request -> request.startsWith("POST /containers/new/start?"));
    }
    @Test void legacyWorkerOnAnotherNetworkIsNotAdoptedOrRemoved() {
        legacy=true; legacyNetwork="other-net"; createMode=true;
        scaler.reconcile(new RuleWorkerPool("default",1,1,1),1);
        assertThat(requests).anyMatch(request -> request.startsWith("POST /containers/new/start?"));
        assertThat(requests).noneMatch(request -> (request.startsWith("POST ") || request.startsWith("DELETE "))
                && request.contains("/containers/legacy"));
    }
}
