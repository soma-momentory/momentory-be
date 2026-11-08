package com.momentory.retrospect.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.momentory.retrospect.domain.NeedActionCatalog;
import com.momentory.retrospect.domain.Needs;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RetrospectDomainConfigurationTest {

    @Test
    void loadsCatalogAsSpringBeanWithoutExternalNotionOrDatabaseAccess() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
            context.register(RetrospectDomainConfiguration.class);
            context.refresh();

            var catalog = context.getBean(NeedActionCatalog.class);
            assertThat(catalog.snapshot().actions()).hasSize(55);
            assertThat(catalog.candidatesFor(Needs.ALL)).hasSize(51);
        }
    }
}
