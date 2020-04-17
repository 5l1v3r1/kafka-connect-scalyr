package com.scalyr.integrations.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.base.Preconditions;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.EntityTemplate;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AddEventsClients provides abstraction for making Scalyr addEvents API calls.
 * It performs JSON object serialization and the addEvents POST request.
 *
 * @see <a href="https://app.scalyr.com/help/api"></a>
 */
public class AddEventsClient implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AddEventsClient.class);

  private final CloseableHttpClient client = HttpClients.createDefault();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpPost httpPost;
  private final String apiKey;

  /** Session ID per Task */
  private final String sessionId = UUID.randomUUID().toString();

  private static final String userAgent = "KafkaConnector/" + VersionUtil.getVersion()
    + " JVM/" + System.getProperty("java.version");

  /**
   * @throws IllegalArgumentException with invalid URL, which will cause Kafka Connect to terminate the ScalyrSinkTask.
   */
  public AddEventsClient(String scalyrUrl, String apiKey) {
    this.apiKey = apiKey;
    this.httpPost = new HttpPost(buildAddEventsUri(scalyrUrl));
    addHeaders(this.httpPost);
  }

  /**
   * Make addEvents POST API call to Scalyr with the events object.
   */
  public void log(List<Event> events) throws Exception {
    log.debug("Calling addEvents with {} events", events.size());

    AddEventsRequest addEventsRequest = new AddEventsRequest()
      .setSession(sessionId)
      .setToken(apiKey)
      .setEvents(events);

    httpPost.setEntity(new EntityTemplate(addEventsRequest::writeJson));
    try (CloseableHttpResponse httpResponse = client.execute(httpPost)) {
      AddEventsResponse addEventsResponse = objectMapper.readValue(httpResponse.getEntity().getContent(), AddEventsResponse.class);
      log.debug("post http code {}, httpResponse {} ", httpResponse.getStatusLine().getStatusCode(), addEventsResponse);
      if (httpResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK || !AddEventsResponse.SUCCESS.equals(addEventsResponse.getStatus())) {
        throw new RuntimeException("addEvents failed with http code " + httpResponse.getStatusLine().getStatusCode()
          + ", message " + addEventsResponse);
      }
    }
  }


  /**
   * Validates url and creates addEvents Scalyr URL
   * @return Scalyr addEvents URI.  e.g. https://apps.scalyr.com/addEvents
   * @throws IllegalArgumentException with invalid Scalyr URL
   */
  private URI buildAddEventsUri(String url) {
    try {
      URIBuilder urlBuilder = new URIBuilder(url);

      // Enforce https for Scalyr connection
      Preconditions.checkArgument((urlBuilder.getScheme() != null && urlBuilder.getHost() != null)
        && ((!"localhost".equals(urlBuilder.getHost()) && "https".equals(urlBuilder.getScheme())) || "localhost".equals(urlBuilder.getHost())),
        "Invalid Scalyr URL: {}", url);

      urlBuilder.setPath("addEvents");
      return  urlBuilder.build();
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Add addEvents POST request headers
   */
  private void addHeaders(HttpPost httpPost) {
    httpPost.addHeader("Content-type", ContentType.APPLICATION_JSON.toString());
    httpPost.addHeader("Accept", ContentType.APPLICATION_JSON.toString());
    httpPost.addHeader("Connection", "Keep-Alive");
    httpPost.addHeader("User-Agent", userAgent);
  }

  @Override
  public void close() {
    try {
      client.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * addEvents Request Data
   */
  public static class AddEventsRequest {
    private String token;
    private String session;
    private List<Event> events;

    public AddEventsRequest setToken(String token) {
      this.token = token;
      return this;
    }

    public AddEventsRequest setSession(String session) {
      this.session = session;
      return this;
    }

    public AddEventsRequest setEvents(List<Event> events) {
      this.events = events;
      return this;
    }

    /**
     * Serializes the AddEventsRequest to JSON, writing the JSON to the `outputStream`.
     */
    public void writeJson(OutputStream outputStream) throws IOException {
      try {
        // Assign log ids for the server level event fields (server, logfile, parser) permutations.
        // Same server level event values are mapped to a logs array entry so the same data is not repeated in the events.
        AtomicInteger logId = new AtomicInteger();
        Map<Event, Integer> logIdMapping = new HashMap<>();  // Event is hashed by server fields = log level fields
        events.forEach(event -> logIdMapping.putIfAbsent(event, logId.getAndIncrement()));

        // Serialize JSON using custom serializers
        final ObjectMapper objectMapper = new ObjectMapper();
        final SimpleModule simpleModule = new SimpleModule("SimpleModule", new Version(1, 0, 0, null, null, null));
        simpleModule.addSerializer(AddEventsRequest.class, new AddEventsRequestSerializer(logIdMapping));
        simpleModule.addSerializer(Event.class, new EventSerializer(logIdMapping));
        objectMapper.registerModule(simpleModule);
        objectMapper.writeValue(outputStream, this);
      } finally {
        outputStream.close();
      }
    }
  }

  /**
   * Custom JsonSerializer for {@link AddEventsRequest}
   * Produces the following addEvents JSON:
   * {
   *   "token":   "xxx",
   *   "session": "yyy",
   *   "events":  [...],
   *   "logs":    [{"id":"1", "attrs":{"serverHost":"", "logfile":"", "parser":""}, {"id":"2", "attrs":{"serverHost":"", "logfile":"", "parser":""}}]
   * }
   */
  public static class AddEventsRequestSerializer extends JsonSerializer<AddEventsRequest> {
    private final Map<Event, Integer> logIdMapping;

    public AddEventsRequestSerializer(Map<Event, Integer> logIdMapping) {
      this.logIdMapping = logIdMapping;
    }

    @Override
    public void serialize(AddEventsRequest addEventsRequest, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
      jsonGenerator.writeStartObject();
      jsonGenerator.writeStringField("token", addEventsRequest.token);
      jsonGenerator.writeStringField("session", addEventsRequest.session);
      jsonGenerator.writeObjectField("events", addEventsRequest.events);
      writeLogs(jsonGenerator);
      jsonGenerator.writeEndObject();
    }

    /**
     * Write logs array:
     * "logs":    [{"id":"1", "attrs":{"serverHost":"", "logfile":"", "parser":""}},
     *             {"id":"2", "attrs":{"serverHost":"", "logfile":"", "parser":""}}]
     */
    private void writeLogs(JsonGenerator jsonGenerator) throws IOException {
      jsonGenerator.writeArrayFieldStart("logs");
      for (Map.Entry<Event, Integer> entry : logIdMapping.entrySet()) {
        writeLogArrayEntry(entry, jsonGenerator);
      }
      jsonGenerator.writeEndArray();
    }

    /**
     * Write single logs array entry:
     * {"id":"1", "attrs":{"serverHost":"", "logfile":"", "parser":""}}
     */
    private void writeLogArrayEntry(Map.Entry<Event, Integer> logEntry, JsonGenerator jsonGenerator) throws IOException {
      final Event event = logEntry.getKey();
      final Integer logId = logEntry.getValue();

      jsonGenerator.writeStartObject();
      jsonGenerator.writeStringField("id", logId.toString());
      jsonGenerator.writeObjectFieldStart("attrs");

      if (event.getServerHost() != null) {
        jsonGenerator.writeStringField("source", event.getServerHost());
      }
      if (event.getLogfile() != null) {
        jsonGenerator.writeStringField("logfile", event.getLogfile());
      }
      if (event.getParser() != null) {
        jsonGenerator.writeStringField("parser", event.getParser());
      }
      jsonGenerator.writeEndObject();
      jsonGenerator.writeEndObject();
    }
  }

  /**
   * Custom JsonSerializer for {@link Event}
   * Produces the following Event JSON:
   * {
   *   "ts": "event timestamp (nanoseconds since 1/1/1970)",
   *   "si": set to the value of sequence_id.  This identifies which sequence the sequence number belongs to.
   *       The sequence_id is the {topic, partition}.
   *   "sn": set to the value of sequence_number.  This is used for deduplication.  This is set to the Kafka parition offset.
   *   "log": index into logs array for log level attributes
   *   "attrs": {"message": set to the log message}
   * }
   */
  public static class EventSerializer extends JsonSerializer<Event> {

    private final Map<Event, Integer> logIdMapping;

    public EventSerializer(Map<Event, Integer> logIdMapping) {
      this.logIdMapping = logIdMapping;
    }

    @Override
    public void serialize(Event event, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
      jsonGenerator.writeStartObject();
      jsonGenerator.writeNumberField("ts", event.getTimestamp());
      jsonGenerator.writeStringField("si", event.getTopic() + "-" + event.getPartition()); // sequence identifier
      jsonGenerator.writeNumberField("sn", event.getOffset()); // sequence number
      writeEventAttrs(event, jsonGenerator);

      Integer logId = logIdMapping.get(event);
      if (logId != null) {
        jsonGenerator.writeStringField("log", logId.toString());
      }

      jsonGenerator.writeEndObject();
    }

    /**
     * Write event attrs:
     * "attrs: {"message": "msg"}
     */
    private void writeEventAttrs(Event event, JsonGenerator jsonGenerator) throws IOException {
      jsonGenerator.writeObjectFieldStart("attrs");
      jsonGenerator.writeStringField("message", event.getMessage());
      jsonGenerator.writeEndObject();
    }
  }

  /**
   * AddEvents API Response object
   */
  @JsonIgnoreProperties(ignoreUnknown = true)  // ignore bytesCharged
  public static class AddEventsResponse {
    public static final String SUCCESS = "success";

    private String status;
    private String message;

    public String getStatus() {
      return status;
    }

    public AddEventsResponse setStatus(String status) {
      this.status = status;
      return this;
    }

    public String getMessage() {
      return message;
    }

    public AddEventsResponse setMessage(String message) {
      this.message = message;
      return this;
    }

    @Override
    public String toString() {
      return "{" +
        "\"status\":\"" + status + '"' +
        ", \"message\":\"" + message + '"' +
        '}';
    }
  }
}
