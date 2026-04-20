package com.bofa.ibox.lockbox.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the application-wide Jackson ObjectMapper.
 *
 * Uses {@link Jackson2ObjectMapperBuilderCustomizer} so that Spring Boot's
 * auto-configured ObjectMapper is customised (not replaced), meaning all
 * Spring MVC / Spring Batch / test infrastructure continues to use the
 * same, consistently configured mapper.
 *
 * Settings applied:
 *  - JavaTimeModule: serialises/deserialises java.time types (LocalDate etc.)
 *  - WRITE_DATES_AS_TIMESTAMPS disabled: dates written as ISO strings ("2026-04-16")
 *  - FAIL_ON_UNKNOWN_PROPERTIES disabled: provider may add fields in future spec versions
 *  - NON_NULL inclusion: omit null fields from serialised output
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
            .modules(new JavaTimeModule())
            .featuresToDisable(
                SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
