import client.ChatClient;
import client.ConnectionManager;
import util.CSVWriter;
import util.Metrics;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import model.ClientMessage;
import model.LatencyReport;
import util.BatchMessageGenerator;
import util.MetricsPrintUtil;
import util.PhaseExecutor;

/**
 * This class is for Initial Phase and Main Phase: Initial Phase sends out 32_000 messages using 32 threads and Main Phase sends out remaining 468_000 messages using different numbers of threads to test out optimal threads
 * It makes improvement from LoadTest1 by extracting websocket connection function and runPhase function and matrix print out function to make it more friendly for testing experiments
 * It includes CSV Writer to write out Per-Message Metrics
 * It calculates and displays statistical analysis
 */

public class LoadTestPart2 {
  public static final ConcurrentHashMap<String, LatencyReport> pendingMessages = new ConcurrentHashMap<>();
  public static final BlockingQueue<LatencyReport> resultsQueue = new LinkedBlockingQueue<>();
  public static final BlockingQueue<ClientMessage> messagesQueue = new LinkedBlockingQueue<>();
  public static final int WARMUP_COUNT = 32_000;
  public static final int WARMUP_THREADS = 32;
  public static final int TOTAL_COUNT = 500_000;
  public static final int NUM_OF_CHAT_ROOMS = 20;
  public static final int DEFAULT_MAIN_PHASE_THREAD = 32;
  public static final ConnectionManager connectionManager = ConnectionManager.getInstance();

  public static void main(String[] args)
      throws Exception {
//    Record start time for overall matrix
    long overallStartTime = System.currentTimeMillis();

//    Extract Input: Allow Thread count override from command line
    int mainPhaseThreads = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_MAIN_PHASE_THREAD;

    System.out.println("===========================================");
    System.out.println("Starting LoadTest Part 2");
    System.out.println("Warmup: " + WARMUP_COUNT + " messages with " + WARMUP_THREADS + " threads");
    System.out.println("Main: " + (TOTAL_COUNT - WARMUP_COUNT) + " messages with " + mainPhaseThreads + " threads");
    System.out.println("===========================================\n");

//    This wsConnectedLatch make sure the main thread wait till all websocket connections are open
    CountDownLatch wsConnectedLatch = new CountDownLatch(NUM_OF_CHAT_ROOMS);
//    This responseLatch ensures the main thread waits until all sent messages receive responses from the server, or a timeout occurs, whichever comes first.
    CountDownLatch warmupResponseLatch = new CountDownLatch(WARMUP_COUNT);

//    Create connection pools inside Connection Manager, pass in warmupResponseLatch for now
    //    String curURI = "ws://localhost:8080/chat/";
    String curURI = "ws://16.147.254.83:8080/chat/";
    connectionManager.setServerBaseUri(curURI);
    System.out.println("Current server is on " + curURI);
    connectionManager.setupConnectionPool(wsConnectedLatch, warmupResponseLatch, resultsQueue, pendingMessages, NUM_OF_CHAT_ROOMS);

//    backgroundExecutor manages two threads: One designated thread for csv writing and one designated thread for message generation
    ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2);
    String outputDir = "results/part2";
    String fileName = "part2_metrics.csv";
    Future<?> csvFuture = backgroundExecutor.submit(new CSVWriter(resultsQueue, outputDir, fileName));
    Future<?> msgGenFuture = backgroundExecutor.submit(new BatchMessageGenerator(messagesQueue, TOTAL_COUNT));

    // Wait for message generation to complete
    msgGenFuture.get();
    System.out.println("Message generation complete\n");
    System.out.println("Queue size: " + messagesQueue.size());

//    Create Phase Executor to run both phases
    PhaseExecutor phaseExecutor = new PhaseExecutor();

//    ==========================WARMUP PHASE============================
    System.out.println(">>> Phase 1: Warmup >>>");
    System.out.println("Sending " + WARMUP_COUNT + " messages with " + WARMUP_THREADS + " threads...");
//    Log warmup start time
    long warmupStartTime = System.currentTimeMillis();

//    Run Warmup Phase
    phaseExecutor.executePhase(WARMUP_THREADS, WARMUP_COUNT, messagesQueue);

    boolean finished = warmupResponseLatch.await(30, TimeUnit.SECONDS);
    if (!finished) {
      System.out.println("Warning: Phase timed out before all ACKs received.");
    }

//    Calculation for warmup phase
    long warmupEndTime = System.currentTimeMillis();
    long warmupTotalTime = warmupEndTime - warmupStartTime;
    int initialFailedMessages = (int) warmupResponseLatch.getCount();
    int initialSuccessMessages = WARMUP_COUNT - initialFailedMessages;

//    =========================MAIN PHASE Set Up=================================
//    Reset metrics for Main Phase
//    Metrics.successMessages.set(0);
//    Calculate Main Phase message count
    int mainMessageCount = TOTAL_COUNT - WARMUP_COUNT;
//    Update response latches for main phase
    CountDownLatch mainResponseLatch = new CountDownLatch(mainMessageCount);
//    Update all clients with new response latch
    for (ChatClient client : connectionManager.getConnectionPool().values()) {
      client.setResponseLatch(mainResponseLatch);
    }
//    Clear out pending messages
    pendingMessages.clear();

//    =========================MAIN PHASE=================================
    System.out.println(">>> Main Phase >>>");
    System.out.println("Sending " + mainMessageCount + " messages with " + mainPhaseThreads + " threads...");
//    Log main phase start time
    long mainStartTime = System.currentTimeMillis();
//    Run Main Phase
    phaseExecutor.executePhase(mainPhaseThreads, mainMessageCount, messagesQueue);

    boolean mainFinished = mainResponseLatch.await(300, TimeUnit.SECONDS);
    if (!mainFinished) {
      System.out.println("Warning: Phase timed out before all ACKs received.");
    }

//    Calculation for main phase
    long mainEndTime = System.currentTimeMillis();
    long mainTotalTime = mainEndTime - mainStartTime;
    int mainFailedMessages = (int) mainResponseLatch.getCount();
    int mainSuccessMessages = mainMessageCount - mainFailedMessages;

//    =======================BOTH PHASE FINISHED, CLEAN UP==================
    // Signal CSV writer to stop
    resultsQueue.put(LatencyReport.POISON_PILL);
    csvFuture.get(30, TimeUnit.SECONDS);
//    Shutdown background executor
    backgroundExecutor.shutdown();
//    Shutdown all websockets in connection manager
    connectionManager.shutdownAll();
    System.out.println("After connections closed");

    // Calculate overall metrics
    long overallEndTime = System.currentTimeMillis();
    long overallTime = overallEndTime - overallStartTime;

//    =======================PRINT OUT MATRIX==================
    System.out.println("=====Load test 2 completed=====");
    MetricsPrintUtil.printPhaseMetrics("Initial Phase", WARMUP_COUNT, initialSuccessMessages, initialFailedMessages, warmupTotalTime, WARMUP_THREADS);
    MetricsPrintUtil.printPhaseMetrics("Main Phase", mainMessageCount, mainSuccessMessages, mainFailedMessages, mainTotalTime, mainPhaseThreads);
    MetricsPrintUtil.printPhaseMetrics("Overall", TOTAL_COUNT, initialSuccessMessages + mainSuccessMessages, initialFailedMessages + mainFailedMessages, overallTime, mainPhaseThreads);
    System.out.println("Total Connections: " + Metrics.connections);
    System.out.println("Total Reconnections: " + Metrics.reconnections);
    System.out.println("Generating detailed statistical analysis...");
    String statsPath = outputDir + "/" + fileName;
    StatisticsGenerator.main(new String[]{statsPath});
  }
}
