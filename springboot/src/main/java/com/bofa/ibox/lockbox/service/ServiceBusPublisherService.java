package com.bofa.ibox.lockbox.service;

import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.bofa.ibox.lockbox.config.LockboxImportProperties;
import com.bofa.ibox.lockbox.model.DuplicateFileEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Publishes domain events to the Azure Service Bus topic.
 *
 * This service is responsible only for building and sending the
 * Service Bus message; it does not send e-mails or construct approval
 * links – those concerns belong to the downstream notification service.
 *
 * Message format:
 * <pre>
 * {
 *   "templateName": "duplicateBOALockboxFileAlert",
 *   "eventName":    "emailNotificationSenderDev",   // profile-specific, from lockbox.import.event-name
 *   "dynamicValues": {
 *     "env": "dev"                                  // from lockbox.import.env
 *   }
 * }
 * </pre>
 *
 * The application property {@code eventName} on the Service Bus message
 * is set to the same value as the JSON {@code eventName} field so topic-
 * subscription SQL filters can route the message to the correct subscriber.
 */
@Service
public class ServiceBusPublisherService {

    private static final Logger log = LoggerFactory.getLogger(ServiceBusPublisherService.class);

    /**
     * Fixed template name consumed by the notification service to select
     * the correct e-mail template.
     */
    static final String TEMPLATE_NAME_DUPLICATE_FILE = "duplicateBOALockboxFileAlert";

    private final ServiceBusSenderClient  senderClient;
    private final ObjectMapper            objectMapper;
    private final LockboxImportProperties props;

    public ServiceBusPublisherService(ServiceBusSenderClient senderClient,
                                      ObjectMapper objectMapper,
                                      LockboxImportProperties props) {
        this.senderClient = senderClient;
        this.objectMapper = objectMapper;
        this.props        = props;
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /**
     * Sends a duplicate-file alert to the configured Service Bus topic.
     *
     * The message body contains:
     * <ul>
     *   <li>{@code templateName} – fixed value identifying the e-mail template</li>
     *   <li>{@code eventName}    – profile-specific routing key (from {@code lockbox.import.event-name})</li>
     *   <li>{@code dynamicValues.env} – active environment label (from {@code lockbox.import.env})</li>
     * </ul>
     * The same {@code eventName} value is also set as a Service Bus application
     * property so topic-subscription SQL filters can route it to the right handler.
     *
     * @param fileId the {@code import_log_id} of the DUPLICATE_PENDING record,
     *               which the notification service uses to generate the approve/reject link
     */
    public void sendDuplicateFileAlert(long fileId) {
        sendEvent(fileId,
                  TEMPLATE_NAME_DUPLICATE_FILE,
                  props.getEventName(),
                  props.getEnv());
    }

    // ----------------------------------------------------------------
    // Internal helper
    // ----------------------------------------------------------------

    /**
     * Builds the {@link DuplicateFileEvent} payload, serialises it to JSON,
     * attaches the {@code eventName} application property for SQL-filter
     * routing, and synchronously delivers the message to the topic.
     *
     * @param fileId       id of the pending log entry
     * @param templateName e-mail template identifier (fixed per event type)
     * @param eventName    profile-specific routing key (used as SQL-filter property)
     * @param env          active environment label injected into dynamicValues
     * @throws RuntimeException if serialisation or delivery fails
     */
    private void sendEvent(long fileId, String templateName, String eventName, String env) {
        try {
            DuplicateFileEvent event = new DuplicateFileEvent();
            event.setTemplateName(templateName);
            event.setEventName(eventName);
            event.setDynamicValues(Map.of("env", env));

            String json = objectMapper.writeValueAsString(event);

            ServiceBusMessage message = new ServiceBusMessage(json);
            // REQUIRED for topic-subscription SQL filter:
            //   eventName = 'emailNotificationSenderDev'  (or profile equivalent)
            message.getApplicationProperties().put("eventName", eventName);

            senderClient.sendMessage(message);
            log.info("Service Bus event sent – templateName='{}', eventName='{}', env='{}', fileId={}",
                    templateName, eventName, env, fileId);

        } catch (Exception e) {
            log.error("Failed to publish Service Bus event '{}' for fileId={}: {}",
                    templateName, fileId, e.getMessage(), e);
            throw new RuntimeException("Failed to publish event", e);
        }
    }
}
