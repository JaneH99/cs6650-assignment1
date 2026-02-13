package client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import model.ClientMessage;
import model.LatencyReport;
import model.ResponseMessage;
import util.BackOffUtil;
import util.Metrics;

/**
 * Standard-compliant WebSocket client designed for high-concurrency load testing.
 * Manages individual room sessions, performance tracking, and automated failure recovery.
 */
@ClientEndpoint
public class ChatClient {

  private final Gson gson = new Gson();
  private final String roomId;
  private final URI serverUri;
  private Session session; // Managed by the Container (Tomcat)

//  Single-threaded scheduler ensures thread-safety for retries and reconnections
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

//  Shared Resources
  private ConcurrentHashMap<String, LatencyReport> pendingMessages;
  private BlockingQueue<LatencyReport> resultsQueue;
  private CountDownLatch wsConnectedLatch;
  private CountDownLatch responseLatch;

//  Reconnection fields
  private boolean initialReconnectionEstablished = false;
  private boolean intendedShutDown = false;
  private int reconnectionAttemptCount = 0;
  private final AtomicBoolean reconnecting = new AtomicBoolean(false);

//  Limit: Allow 5 reconnection attemp
  private static final int MAX_RECONNECTION_ALLOWED = 5;
//  Limit: Allow 5 resend message attempt
  private static final int MAX_SEND_ALLOWED = 5;
// Update lastSeen for heartbeat mechanism
  private volatile long lastSeen = System.currentTimeMillis();

  /**
   * Creates a ChatClient instance for connecting to a WebSocket server
   *
   * This client is designed for performance testing. It tracks in-flight
   * messages, records round-trip latency for each message, and coordinates
   * connection and response synchronization using latches.
   *
   * @param serverUri
   *        URI of the WebSocket server to connect
   *
   * @param pendingMessages
   *        A map of messageId to LatencyReport used to track messages that have
   *        been sent but not yet acknowledged by the server. The send timestamp
   *        is recorded here and matched when a response is received.
   *
   * @param resultsQueue
   *        A thread-safe queue where completed LatencyReport objects are placed
   *        after a response is received. This queue is consumed by CSV writer.
   *
   * @param wsConnectedLatch
   *        A latch that is decremented when a WebSocket connection is
   *        successfully established. Used to measure connection overhead
   *        and to block test execution until all websockets are connected.
   *
   * @param responseLatch
   *        A latch used to wait for expected server responses. It is decremented
   *        when a response for a send message that is received, allowing the test
   *        driver to wait for all messages to complete.
   *
   * @param roomId
   *        Identifier of the chat room this client joins or sends messages to.
   */

  public ChatClient(URI serverUri, ConcurrentHashMap<String, LatencyReport> pendingMessages,
      BlockingQueue<LatencyReport> resultsQueue, CountDownLatch wsConnectedLatch, CountDownLatch responseLatch, String roomId) {
    this.serverUri = serverUri;
    this.pendingMessages = pendingMessages;
    this.resultsQueue = resultsQueue;
    this.wsConnectedLatch = wsConnectedLatch;
    this.responseLatch = responseLatch;
    this.roomId = roomId;
  }


  /**
   * Triggers the initial WebSocket handshake.
   */
  public void connect() {
    try {
      WebSocketContainer container = ContainerProvider.getWebSocketContainer();
      // This will trigger the @OnOpen method
      container.connectToServer(this, serverUri);
    } catch (Exception e) {
      // If the initial connection fails, we handle it via the reconnect logic
      System.err.println("Initial connection failed for room " + roomId + ": " + e.getMessage());
      attemptReconnect();
    }
  }


  /**
   * Lifecycle event: Handshake successful.
   * Invoked when handshake is successful, indicating that the client is ready
   * to send and receive messages.
   */

  @OnOpen
  public void onOpen(Session session) {
    this.session = session;
    this.lastSeen = System.currentTimeMillis();
    this.reconnectionAttemptCount = 0;
//    Only increment global metrics on the first successful connection
    if (!initialReconnectionEstablished) {
      initialReconnectionEstablished = true;
      wsConnectedLatch.countDown();
      Metrics.connections.getAndIncrement();
    }
//    System.out.println("Connection established for room: " + roomId);
  }

  /**
   * Invoked when a text message is received from the WebSocket server.
   * Calculates Round-Trip Time (RTT) and updates the LatencyReport.
   * It distinguishes between error responses and successful acknowledgements,
   * then updates the corresponding latency record for the message.
   */
  @OnMessage
  public void onMessage(String message) {
    this.lastSeen = System.currentTimeMillis();
    long receiveTime = System.currentTimeMillis();

    try {
//      Parse Server Response into JSON
      JsonObject json = gson.fromJson(message, JsonObject.class);
//      Identify Error Message. If it is error message, it is logged but not included in latency matrix
      if (json.has("errorType")) {
        System.err.println("Server Error [" + roomId + "]: " + json.get("errorMessage"));
        return;
      }
      /*
       * Handle successful response:
       *
       * 1. Extract messageId from server acknowledgement
       * 2. Retrieve the matching pending latency report
       * 3. Record receive timestamp
       * 4. Update message status
       * 5. Push completed latency report to results queue for CSV logging
       */
      ResponseMessage response = gson.fromJson(message, ResponseMessage.class);
      String messageId = response.getMessageId();
      LatencyReport latencyReport = pendingMessages.remove(messageId);

      if (latencyReport != null) {
        latencyReport.setReceiveTime(receiveTime);
        latencyReport.setStatusCode(response.getStatus().equals("SUCCESS") ? "SUCCESS" : "UNKNOWN");
        responseLatch.countDown();
        if (resultsQueue != null) {
          resultsQueue.add(latencyReport);
        }
      }
    } catch (Exception e) {
      System.err.println("Failed to parse message in room " + roomId + ": " + e.getMessage());
    }
  }

  /**
   * Lifecycle event: Socket closed.
   * If it is invoked unintendedly, trigger exponential backoff connection
   *
   */

  @OnClose
  public void onClose(Session session, CloseReason reason) {
    this.session = null;
//    If the server closes the socket unexpectedly and the maximum reconnection attempts haven’t reached, we will try to reconnect it
//    System.out.println("Connection is closing for legit or not legit reason for room " + roomId);
    if (!intendedShutDown) {
      if (reconnectionAttemptCount < MAX_RECONNECTION_ALLOWED) {
        System.out.println("Unexpected Websocket Closure in room " + roomId + ". Reason: " + reason + ". Attempting to reconnect");
        attemptReconnect();
      } else {
        System.out.println("Maximum reconnection has been reached for room " + roomId + ". Connection will not be retried");
      }
    }
  }

  /**
   * Invoked when an error occurs on the WebSocket connection.
   *
   * This method logs the error details and attempts to reconnect if:
   * - The client did not intentionally shut down the connection, and
   * - The WebSocket is currently closed, and
   * - The maximum number of reconnection attempts has not been reached.
   */

  @OnError
  public void onError(Session session, Throwable throwable) {
    System.err.println("WebSocket Error in room " + roomId + ": " + throwable.getMessage());
    if (!intendedShutDown && (session == null || !session.isOpen())) {
      if (reconnectionAttemptCount < MAX_RECONNECTION_ALLOWED) {
        System.out.println("Websocket Error in room " + roomId + ". Attempting to reconnect");
        attemptReconnect();
      } else {
        System.out.println("Websocket Error in room " + roomId + ". Max reconnection attempt reached for the room room. Connection will not be retried");
      }
    }
  }

  /**
   * Serializes and sends a message.
   * It update the message's timestamp, serializes the message into json and sends the message using the retry mechanism to handle send failures
   * @param msg
   *        The ClientMessage object to be sent.
   */
  public void sendMsg(ClientMessage msg) {
//    Update timestamp to current time and send message
    msg.setTimestamp(Instant.now().toString());
    String json = gson.toJson(msg);
    sendMsgWithRetry(msg, json, 0);
  }

  /**
   * Sends a message through the WebSocket with retry logic and tracks latency.
   *
   * This method handles sending a message, creating a latency report for RTT
   * measurement, and retrying the send in case of failures. It is designed
   * to support performance testing by tracking successful and failed messages.
   */
  public void sendMsgWithRetry(ClientMessage msg, String json, int attempt) {
    if (session == null || !session.isOpen()) {
      retrySend(msg, json, attempt);
      return;
    }
//    Prevent multiple thread access the same session
    synchronized (this.session) {
      try {
        // Standard JSR 356 async send
        session.getAsyncRemote().sendText(json);
        LatencyReport latencyReport = new LatencyReport(msg.getMessageType(), System.currentTimeMillis(),
            msg.getRoomId());
        pendingMessages.put(msg.getMessageId(), latencyReport);
      } catch (Exception e) {
        retrySend(msg, json, attempt);
      }
    }
  }
  /**
   * Schedules a message resend with exponential backoff if the socket is busy or failing.
   */
  private void retrySend(ClientMessage msg, String json, int attempt) {
    int nextAttempt = attempt + 1;
    if (nextAttempt < MAX_SEND_ALLOWED) {
      int waitTime = BackOffUtil.calculateExponentialBackoff(nextAttempt);
      scheduler.schedule(() -> sendMsgWithRetry(msg, json, nextAttempt), waitTime, TimeUnit.MILLISECONDS);
    }
  }


  /**
   * Re-establishes session using serverUri.
   * Uses AtomicBoolean to ensure only one reconnection task is queued at a time.
   * This method is triggered when a connection is closed unexpectedly or an error occurs. It ensures that:
   * 1. Only one reconnection attempt runs at a time using the `reconnecting` flag.
   * 2. Reconnection attempts are counted for metrics (`Metrics.reconnections` and `reconnectionAttemptCount`).
   * 3. The wait time before retrying increases exponentially based on the attempt count.
   */

  private void attemptReconnect() {
    if (!reconnecting.compareAndSet(false, true)) {
      return;
    }
    Metrics.reconnections.getAndIncrement();
    reconnectionAttemptCount++;
    int waitTime = BackOffUtil.calculateExponentialBackoff(reconnectionAttemptCount);
    scheduler.schedule(() -> {
      try {
        connect();
        System.out.println("Reconnection for room"+ roomId + "this is the " + reconnectionAttemptCount + " time try");
      } catch (Exception e) {
        System.out.println("Reconnection failed for " + roomId);
      } finally {
        reconnecting.set(false);
      }
    }, waitTime, TimeUnit.MILLISECONDS);
  }


//  Disables automatic reconnection for this WebSocket client.
  public void disableReconnection() {
    this.intendedShutDown = true;
  }

  /**
   * Gracefully shuts down the WebSocket client.
   *
   * - Disables reconnection attempts,
   * - Terminates scheduled tasks,
   * - Closes the WebSocket connection
   */
  public void cleanup() {
    disableReconnection();
    scheduler.shutdown();
    this.close();
  }

  public void close() {
    try {
      if (this.session != null && this.session.isOpen()) {
        this.session.close();
      }
    } catch (IOException e) {
      System.err.println("Notice: Session for room " + roomId + " did not close cleanly.");
    } finally {
      this.session = null;
    }
  }

  /**
   * Setter for responseLatch, to separate countdown latches for warmup phase and main phase
   */
  public void setResponseLatch(CountDownLatch newLatch) {
    this.responseLatch = newLatch;
  }


//  Helper function to get lastSeen timestamp, used by
  public long getLastSeen() {
    return lastSeen;
  }

  /**
   * Heartbeat helper called by ConnectionManager.
   */
  public void sendPing() {
    if (session != null && session.isOpen()) {
      try {
        // Send a standard WebSocket ping frame
        session.getBasicRemote().sendPing(ByteBuffer.wrap(new byte[0]));
      } catch (IOException e) {
        System.err.println("Failed to send ping for room " + roomId);
      }
    }
  }

  //  Update getLastSeen timestamp when PongMessage is echo back from server
  @OnMessage
  public void onPong(PongMessage pongMessage) {
    this.lastSeen = System.currentTimeMillis(); // Heartbeat check
  }

//  Check status
  public boolean isOpen() {
    return session != null && session.isOpen();
  }

}
