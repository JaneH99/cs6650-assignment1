# WebSocket Chat Load Testing Project

This project is a multi-module Maven application designed to stress-test a Java-based WebSocket server deployed on **AWS EC2**. It measures round-trip latency (RTT) and throughput across multiple testing phases.

---

## Project Structure

The project is organized into modular components to maximize code reuse and maintain a clear separation of concerns:

* **`server`**: The backend WebSocket implementation. It includes `ChatWebSocket` for room-based messaging and an `AppStatusListener` to manage server lifecycle.
* **`shared-core`**: A central library shared by all client modules. It contains:
    * **`client`**: Core networking logic, including the `ChatClient` and the `ConnectionManager` singleton.
    * **`model`**: Standardized POJOs such as `ClientMessage`, `LatencyReport`, and `ResponseMessage`.
    * **`util`**: Infrastructure tools including `BackOffUtil` for retries, `CSVWriter` for data logging, `BatchMessageGenerator` for generating message and `MessageSender` for sending messages.
* **`client-part1`**: Initial testing module containing `LoadTestPart1` for baseline evaluations.
* **`client-part2`**: Advanced testing module containing `LoadTestPart2` and the `StatisticsGenerator` for deep performance analysis.

---

## Getting Started

### 1. Prerequisites
* **Java 11+** (Required for modern concurrent collections and `Instant` precision).
* **Maven 3.8+**.
* **Tomcat 9+** (For server deployment on EC2).

### 2. Build and Deployment

#### 1. Build the Project
From the root directory, install the shared library and package all modules.
```bash
mvn clean install -DskipTests
```

#### 2. Deploy to AWS EC2
Once the build is successful, follow these steps to host your server:
- Locate the WAR: Find the chat.war file in the server/target/ directory.
- Upload: Transfer the WAR file to your AWS EC2 instance using SCP or a similar tool.
- Tomcat Setup: Move the WAR file into the /opt/tomcat/webapps/ folder.
-Verify: Confirm the server is running by visiting http://<EC2-IP>:8080/chat/health.

#### 3. Run the Load Test
***Make sure you change curURI to "ws://<EC2-IP>:8080/chat/" in LoadTest1 and LoadTest2 before you run the test***
Navigate to the client-part1 directory to execute the baseline baseline performance test.
```bash
mvn exec:java -Dexec.mainClass="LoadTestPart1"
```
Rebuild and Run (Recommended if code changed. eg, if you changed the curURI):
```bash
mvn clean compile exec:java -Dexec.mainClass="LoadTestPart1"
```

#### 4. Running Client Part 2
Navigate to the client-part2 directory for advanced load testing and statistics generation.
The csv file for latence can be found in results/part2
```bash
mvn exec:java -Dexec.mainClass="LoadTestPart2"
```
You have an option to run client-part2 with customized threads number. Pass the thread number to the argument.
```bash
mvn exec:java -Dexec.mainClass="part2.LoadTestPart2" -Dexec.args="128"
```
LoadTestPart2 will automatically call the StatisticsGenerator class to generate statistical analysis from data in csv. Alternatively, you can call it explicitly and pass in the path of the csv file
```bash
mvn exec:java -Dexec.mainClass="StatisticsGenerator" -Dexec.args="{csv file path}"

