package com.bofa.ibox.lockbox.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configures the application-wide Jackson ObjectMapper.
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
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }
}
