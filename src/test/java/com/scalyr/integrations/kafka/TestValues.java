package com.scalyr.integrations.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Values to use for testing
 */
public abstract class TestValues {
  public static final String TOPIC_VALUE = "LogsTopic";
  public static final String MESSAGE_VALUE = "Test log message";
  public static final String LOGFILE_VALUE = "/var/log/syslog";
  public static final String SERVER_VALUE = "server";
  public static final String PARSER_VALUE = "systemLogPST";
  public static final String API_KEY_VALUE = "abc123";

  public static final String ADD_EVENTS_RESPONSE_SUCCESS;
  public static final String ADD_EVENTS_RESPONSE_SERVER_BUSY;

  static {
    // AddEventsResponse response messages
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      ADD_EVENTS_RESPONSE_SUCCESS = objectMapper.writeValueAsString(new AddEventsClient.AddEventsResponse()
        .setStatus(AddEventsClient.AddEventsResponse.SUCCESS).setMessage(AddEventsClient.AddEventsResponse.SUCCESS));
      ADD_EVENTS_RESPONSE_SERVER_BUSY = objectMapper.writeValueAsString(new AddEventsClient.AddEventsResponse()
        .setStatus("error/server/busy").setMessage("Requests are throttled.  Try again later"));
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

}
