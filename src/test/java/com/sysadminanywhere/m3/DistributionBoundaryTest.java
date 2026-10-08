package com.sysadminanywhere.m3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sysadminanywhere.m3.extensions.ExecutionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ClassUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DistributionBoundaryTest {
    @Test void distributionContainsOnlyItsOwnImplementationAndStartsInCommunityMode() throws Exception {
        boolean full="full".equals(System.getProperty("m3.distribution","community"));
        var loader=getClass().getClassLoader();
        assertThat(ClassUtils.isPresent("com.sysadminanywhere.m3.community.CommunityLicenseView",loader)).isFalse();
        assertThat(loader.getResource("i18n/extra_ui_ru.json") != null).isEqualTo(full);
        assertThat(ClassUtils.isPresent("com.sysadminanywhere.m3.ScaleApplication",loader)).isEqualTo(full);
        assertThat(ClassUtils.isPresent("com.sysadminanywhere.m3.messaging.service.ScaleLicenseService",loader)).isEqualTo(full);
        assertThat(ClassUtils.isPresent("com.sysadminanywhere.m3.community.CommunityExecutionPolicy",loader)).isEqualTo(!full);
        assertThat(ClassUtils.isPresent("com.sysadminanywhere.m3.messaging.api.ScaleLicenseApi",loader)).isEqualTo(full);
        assertThat(ClassUtils.isPresent("com.sysadminanywhere.m3.messaging.ui.ScaleLicenseView",loader)).isEqualTo(full);
        var context=new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        Class<?> policy=Class.forName(full?"com.sysadminanywhere.m3.scale.ScaleExecutionPolicy":"com.sysadminanywhere.m3.community.CommunityExecutionPolicy");
        context=context.withUserConfiguration(policy);
        if(full) context=context.withUserConfiguration(Class.forName("com.sysadminanywhere.m3.messaging.service.ScaleLicenseService"));
        context.run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(ExecutionPolicy.class);
            var executionPolicy=app.getBean(ExecutionPolicy.class);
            if (full) assertThat(executionPolicy.workerEnvironment()).containsKey("M3_LICENSE_PUBLIC_KEY");
            else assertThat(executionPolicy.workerEnvironment()).isEmpty();
            var state=executionPolicy.state();
            assertThat(state.tier()).isEqualTo("Community");
            assertThat(state.maxWorkers()).isEqualTo(1);
            assertThat(state.autoScale()).isFalse();
            assertThat(state.externalAuthentication()).isFalse();
        });
    }
}
