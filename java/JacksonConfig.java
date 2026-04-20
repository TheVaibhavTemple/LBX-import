package com.bofa.ibox.lockbox.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Manually registers the Jackson {@link ObjectMapper} bean.
 *
 * <p>This is necessary because the project has no {@code spring-boot-starter-web},
 * which is the usual trigger for Spring Boot's Jackson auto-configuration.
 * Without this bean, {@link com.exelatech.ibox.service.LockboxImportService}
 * cannot be injected with an {@code ObjectMapper}.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Support LocalDate / LocalDateTime deserialization from ISO strings
        mapper.registerModule(new JavaTimeModule());

        // Read dates as strings (e.g. "2025-04-01"), not as numeric timestamps
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Don't fail if the JSON contains fields not defined in the DTO
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        return mapper;
    }
}
