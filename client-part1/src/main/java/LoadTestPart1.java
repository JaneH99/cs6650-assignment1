import client.ConnectionManager;
import java.util.concurrent.TimeoutException;
import util.MetricsPrintUtil;
import java.net.URISyntaxException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import model.ClientMessage;
import model.LatencyReport;
import util.BatchMessageGenerator;
import util.PhaseExecutor;

/**
 * This class is for Initial Phase Testing. It creates 32 threads and each thread send 1000 messages and print out basic performance matrix in Terminal
 */

public class LoadTestPart1 {
  public static final ConcurrentHashMap<String, LatencyReport> pendingMessages = new ConcurrentHashMap<>();
  public static final BlockingQueue<ClientMessage> messagesQueue = new LinkedBlockingQueue<>();
  public static final int NUM_OF_MESSAGES = 32_000;
  public static final int NUM_OF_THREADS = 32;
  public static final int NUM_OF_CHAT_ROOMS = 20;
  public static final ConnectionManager connectionManager = ConnectionManager.getInstance();

  public static void main(String[] args)
      throws URISyntaxException, InterruptedException, ExecutionException, TimeoutException {

//    This wsConnectedLatch make sure the main thread wait till all websocket connections are open
    CountDownLatch wsConnectedLatch = new CountDownLatch(NUM_OF_CHAT_ROOMS);
//    This responseLatch ensures the main thread waits until all sent messages receive responses from the server, or a timeout occurs, whichever comes first.
    CountDownLatch responseLatch = new CountDownLatch(NUM_OF_MESSAGES);

//    Create connection pools inside Connection Manager
//    String curURI = "ws://localhost:8080/chat/";
    String curURI = "ws://16.147.254.83:8080/chat/";
    connectionManager.setServerBaseUri(curURI);
    System.out.println("Current server is on " + curURI);
    connectionManager.setupConnectionPool(wsConnectedLatch, responseLatch, null, pendingMessages, NUM_OF_CHAT_ROOMS);

//     One designated thread for message generation
    ExecutorService backgroundExecutor = Executors.newFixedThreadPool(1);
    Future<?> msgGenFuture = backgroundExecutor.submit(new BatchMessageGenerator(messagesQueue, NUM_OF_MESSAGES));

//    Wait for message generation to complete
    msgGenFuture.get();
    System.out.println("Message generation complete\n");

//    ExecutorService for producer threads
    ExecutorService executorService = Executors.newFixedThreadPool(NUM_OF_THREADS);

//    This producerCountdownLatch make sure the main thread wait till all message sending threads finish sending messages
    CountDownLatch producerCountdownLatch = new CountDownLatch(NUM_OF_THREADS);
//    Record start time
    long startTime = System.currentTimeMillis();

//    Create PhaseExecutor to run the test
    PhaseExecutor phaseExecutor = new PhaseExecutor();
    phaseExecutor.executePhase(NUM_OF_THREADS, NUM_OF_MESSAGES,messagesQueue);

    // Check if all messages are acknowledged by server. use generous wait time here, and will return true early if all acks arrive
    boolean allResponsesReceived = responseLatch.await(30, TimeUnit.SECONDS);

    if (!allResponsesReceived) {
      System.out.println("Response latch timeout reached");
    }
    // Calculate stats
    long endTime = System.currentTimeMillis();
    long totalTime = endTime - startTime;
    int failedMessages = (int) responseLatch.getCount();
    int successMessages = NUM_OF_MESSAGES - failedMessages;

    // Executor stop accepting any more tasks to run
    executorService.shutdown();
    // Block main thread until all tasks are finished and all threads are idle or 30 seconds time out
    if (executorService.awaitTermination(30,TimeUnit.SECONDS)) {
      System.out.println("All tasks completed within timeout");
    } else {
      System.out.println("Timeout reached! Forcing shutdown");
    }

//    Shutdown background executor
    backgroundExecutor.shutdown();
//    Close all connections
    connectionManager.shutdownAll();

    MetricsPrintUtil.printPhaseMetrics("Basic Load Test", NUM_OF_MESSAGES, successMessages, failedMessages, totalTime, NUM_OF_THREADS);
  }
}
