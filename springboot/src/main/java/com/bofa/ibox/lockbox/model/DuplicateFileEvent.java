package com.bofa.ibox.lockbox.model;

import java.util.Map;

/**
 * Payload published to the Azure Service Bus topic when a duplicate
 * lockbox file is detected (based on file name).
 *
 * Message shape:
 * <pre>
 * {
 *   "templateName": "duplicateBOALockboxFileAlert",
 *   "eventName":    "emailNotificationSenderDev",     // profile-specific
 *   "dynamicValues": {
 *     "env": "dev"                                    // active Spring profile
 *   }
 * }
 * </pre>
 *
 * {@code templateName} identifies which e-mail template the notification
 * service should render.  {@code eventName} is also set as an application
 * property on the Service Bus message so topic-subscription SQL filters
 * can route the message to the correct subscriber.
 */
public class DuplicateFileEvent {

    /** Fixed name of the e-mail template in the notification service. */
    private String templateName;

    /** Profile-specific routing key (also used as SQL-filter application property). */
    private String eventName;

    /** Dynamic values injected into the e-mail template (e.g. environment label). */
    private Map<String, String> dynamicValues;

    public DuplicateFileEvent() {}

    public DuplicateFileEvent(String templateName, String eventName,
                              Map<String, String> dynamicValues) {
        this.templateName  = templateName;
        this.eventName     = eventName;
        this.dynamicValues = dynamicValues;
    }

    // ---- Getters & Setters ----

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public Map<String, String> getDynamicValues() {
        return dynamicValues;
    }

    public void setDynamicValues(Map<String, String> dynamicValues) {
        this.dynamicValues = dynamicValues;
    }
}
