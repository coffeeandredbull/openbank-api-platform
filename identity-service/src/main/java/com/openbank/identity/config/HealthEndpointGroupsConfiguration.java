package com.openbank.identity.config;

import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.actuate.health.AdditionalHealthEndpointPath;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

@Configuration(proxyBeanMethods = false)
public class HealthEndpointGroupsConfiguration {

    private static final String REDIS_CONTRIBUTOR = "redis";
    private static final String REDIS_HEALTH_GROUP = "redisHealth";

    @Bean
    HealthEndpointGroups healthEndpointGroups(HealthEndpointProperties properties,
            StatusAggregator statusAggregator,
            HttpCodeStatusMapper httpCodeStatusMapper) {
        HealthEndpointGroup primary = new ConfiguredGroup(name -> !REDIS_CONTRIBUTOR.equals(name),
                properties.getShowComponents(), properties.getShowDetails(), properties.getRoles(),
                statusAggregator, httpCodeStatusMapper);
        HealthEndpointGroup redisHealth = new ConfiguredGroup(REDIS_CONTRIBUTOR::equals,
                Show.ALWAYS, Show.NEVER, properties.getRoles(),
                statusAggregator, httpCodeStatusMapper);
        return HealthEndpointGroups.of(primary, Map.of(REDIS_HEALTH_GROUP, redisHealth));
    }

    static final class ConfiguredGroup implements HealthEndpointGroup {

        private final Predicate<String> membership;
        private final Show showComponents;
        private final Show showDetails;
        private final Collection<String> roles;
        private final StatusAggregator statusAggregator;
        private final HttpCodeStatusMapper httpCodeStatusMapper;

        private ConfiguredGroup(Predicate<String> membership, Show showComponents, Show showDetails,
                Collection<String> roles, StatusAggregator statusAggregator,
                HttpCodeStatusMapper httpCodeStatusMapper) {
            this.membership = membership;
            this.showComponents = showComponents;
            this.showDetails = showDetails;
            this.roles = roles;
            this.statusAggregator = statusAggregator;
            this.httpCodeStatusMapper = httpCodeStatusMapper;
        }

        @Override
        public boolean isMember(String name) {
            return membership.test(name);
        }

        @Override
        public boolean showComponents(SecurityContext securityContext) {
            Show effective = showComponents != null ? showComponents : showDetails;
            return effective.isShown(securityContext, roles);
        }

        @Override
        public boolean showDetails(SecurityContext securityContext) {
            return showDetails.isShown(securityContext, roles);
        }

        @Override
        public StatusAggregator getStatusAggregator() {
            return statusAggregator;
        }

        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() {
            return httpCodeStatusMapper;
        }

        @Override
        public AdditionalHealthEndpointPath getAdditionalPath() {
            return null;
        }
    }
}
