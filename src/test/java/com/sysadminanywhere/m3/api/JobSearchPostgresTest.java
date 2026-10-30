package com.sysadminanywhere.m3.api;

import com.sysadminanywhere.m3.test.DisposablePostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(DisposablePostgres.class)
@EnabledIfEnvironmentVariable(named="M3_TEST_POSTGRES_URL", matches=".+")
class JobSearchPostgresTest {
    @Test void filtersAndPaginatesWithoutLosingOldUncertainJobs(DriverManagerDataSource ds) {
        var jdbc=new JdbcTemplate(ds); var first=UUID.randomUUID(); var other=UUID.randomUUID();
        jdbc.update("insert into community.message_record(id,source_id,checksum,original_name,storage_key,status) values (?,'a',?,'a','a','RECEIVED'),(?,'b',?,'b','b','RECEIVED')",first,first.toString(),other,other.toString());
        jdbc.update("insert into community.execution_job(id,message_id,rule_id,rule_version,execution_group,status,created_at) values (?,?,'r',1,'alpha','UNCERTAIN',now()-interval '2 days'),(?,?,'r',1,'beta','DELIVERED',now())",UUID.randomUUID(),first,UUID.randomUUID(),other);
        var api=new OperationsController(jdbc);
        var found=api.searchJobs("UNCERTAIN","alpha","a",100,0);
        assertEquals(1,found.size()); assertEquals(first,found.get(0).messageId());
        assertTrue(api.searchJobs("UNCERTAIN","alpha","b",100,0).isEmpty());
        assertEquals(1,api.searchJobs(null,null,null,1,1).size());
        assertTrue(api.searchJobs("UNCERTAIN' OR 1=1 --",null,null,100,0).isEmpty());
    }
}
