package com.bofa.ibox.lockbox.config;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the Azure Service Bus sender client used to publish
 * duplicate-file alert events to the topic.
 *
 * Connection string and topic name are bound from the profile-specific
 * {@code azure.servicebus.*} properties in application.yml.
 */
@Configuration
public class ServiceBusConfig {

    @Value("${azure.servicebus.connection-string}")
    private String connectionString;

    @Value("${azure.servicebus.topic-name}")
    private String topicName;

    /**
     * Creates a synchronous Service Bus sender client scoped to the
     * configured topic.  The bean is closed automatically by Spring on
     * shutdown (ServiceBusSenderClient implements AutoCloseable).
     *
     * @return a ready-to-use {@link ServiceBusSenderClient}
     */
    @Bean
    public ServiceBusSenderClient senderClient() {
        return new ServiceBusClientBuilder()
                .connectionString(connectionString)
                .sender()
                .topicName(topicName)
                .buildClient();
    }
}
