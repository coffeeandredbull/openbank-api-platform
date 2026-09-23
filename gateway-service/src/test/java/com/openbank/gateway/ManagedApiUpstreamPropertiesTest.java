package com.openbank.gateway;

import com.openbank.gateway.config.ManagedApiUpstreamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedApiUpstreamPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ManagedApiUpstreamProperties.class);

    @Test
    void defaultTargetIsTheLocalDevelopmentHost() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ManagedApiUpstreamProperties properties = context.getBean(ManagedApiUpstreamProperties.class);
            assertThat(properties.targetUrl()).isEqualTo("http://localhost:8084");
        });
    }

    @Test
    void environmentOverrideIsHonored() {
        runner.withPropertyValues("MANAGED_API_TARGET_URL=https://runtime.openbank.example:9443")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ManagedApiUpstreamProperties properties = context.getBean(ManagedApiUpstreamProperties.class);
                    assertThat(properties.targetUrl())
                            .isEqualTo("https://runtime.openbank.example:9443");
                });
    }

    @Test
    void validHttpUrlIsAccepted() {
        runner.withPropertyValues("MANAGED_API_TARGET_URL=http://managed.internal:9004/base")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ManagedApiUpstreamProperties properties = context.getBean(ManagedApiUpstreamProperties.class);
                    assertThat(properties.targetUrl()).isEqualTo("http://managed.internal:9004/base");
                });
    }

    @Test
    void validHttpsUrlIsAccepted() {
        runner.withPropertyValues("MANAGED_API_TARGET_URL=https://apis.example.org")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ManagedApiUpstreamProperties properties = context.getBean(ManagedApiUpstreamProperties.class);
                    assertThat(properties.targetUrl()).isEqualTo("https://apis.example.org");
                });
    }

    @Test
    void emptyValueIsRejectedAtStartup() {
        runner.withPropertyValues("MANAGED_API_TARGET_URL=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void malformedUriIsRejectedAtStartup() {
        runner.withPropertyValues("MANAGED_API_TARGET_URL=http://exa{mple}.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingHostIsRejectedAtStartup() {
        runner.withPropertyValues("MANAGED_API_TARGET_URL=http:///orders")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unsupportedSchemeIsRejectedAtStartup() {
        runner.withPropertyValues("MANAGED_API_TARGET_URL=ftp://example.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void userinfoIsRejectedAtStartup() {
        runner.withPropertyValues("MANAGED_API_TARGET_URL=http://user:secret@example.com")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void fragmentIsRejectedAtStartup() {
        runner.withPropertyValues("MANAGED_API_TARGET_URL=http://example.com/orders#overview")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void whitespaceIsRejectedAtStartup() {
        runner.withPropertyValues("MANAGED_API_TARGET_URL=http://example.com/orders local")
                .run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues("MANAGED_API_TARGET_URL=http://example .com")
                .run(context -> assertThat(context).hasFailed());
    }
}