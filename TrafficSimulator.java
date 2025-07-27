import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TrafficSimulator {

    // Default configuration values
    private static String TARGET_URL = "http://localhost:8000/front-page.html";
    private static int NUMBER_OF_REQUESTS = 100;
    private static int CONCURRENT_USERS = 10;
    private static int REQUEST_INTERVAL_MS = 100; // Delay between requests from a single "user" thread (ms)
    private static final String ERROR_LOG_FILE = "traffic_simulator_errors.log";

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final AtomicInteger completedRequests = new AtomicInteger(0);
    private static final AtomicInteger successfulRequests = new AtomicInteger(0);
    private static final AtomicInteger failedRequests = new AtomicInteger(0);
    private static final List<Long> successfulResponseTimes = Collections.synchronizedList(new ArrayList<>()); // Store all successful response times
    private static PrintWriter errorLogger;

    public static void main(String[] args) {
        // Parse command-line arguments
        if (args.length == 4) {
            TARGET_URL = args[0];
            NUMBER_OF_REQUESTS = Integer.parseInt(args[1]);
            CONCURRENT_USERS = Integer.parseInt(args[2]);
            REQUEST_INTERVAL_MS = Integer.parseInt(args[3]);
        } else if (args.length != 0) {
            System.out.println("Usage: java TrafficSimulator [TARGET_URL] [NUMBER_OF_REQUESTS] [CONCURRENT_USERS] [REQUEST_INTERVAL_MS]");
            System.out.println("Using default values if no arguments or incorrect number of arguments provided.");
        }

        try {
            errorLogger = new PrintWriter(new FileWriter(ERROR_LOG_FILE, true)); // Append to file
        } catch (IOException e) {
            System.err.println("Could not open error log file: " + e.getMessage());
            return; // Exit if logging fails
        }

        System.out.println("Starting Java Traffic Simulator...");
        System.out.println("Target URL: " + TARGET_URL);
        System.out.println("Concurrent Users: " + CONCURRENT_USERS);
        System.out.println("Total Requests to Send: " + NUMBER_OF_REQUESTS);
        System.out.println("Request Interval per user: " + REQUEST_INTERVAL_MS + " ms");
        System.out.println("Error logs will be written to: " + ERROR_LOG_FILE);
        System.out.println("--------------------------------------------------");

        long startTime = System.currentTimeMillis();

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CONCURRENT_USERS);

        for (int i = 0; i < NUMBER_OF_REQUESTS; i++) {
            final int requestNumber = i + 1;
            // Schedule requests distributed over time to simulate a continuous load from concurrent users
            scheduler.schedule(() -> {
                sendRequest(requestNumber);
            }, (long) (i * REQUEST_INTERVAL_MS / (double)CONCURRENT_USERS), TimeUnit.MILLISECONDS);
        }

        scheduler.shutdown();

        try {
            boolean finished = scheduler.awaitTermination(90, TimeUnit.SECONDS); // Increased max wait time
            if (!finished) {
                System.err.println("Warning: Not all requests completed within the timeout period.");
                errorLogger.println("Warning: Not all requests completed within the timeout period.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Traffic simulation was interrupted: " + e.getMessage());
            errorLogger.println("Traffic simulation was interrupted: " + e.getMessage());
        } finally {
            errorLogger.close(); // Ensure the error log file is closed
        }

        long endTime = System.currentTimeMillis();
        long totalDurationMs = endTime - startTime;

        System.out.println("\n--------------------------------------------------");
        System.out.println("Traffic Simulation Finished!");
        System.out.println("Total requests attempted: " + NUMBER_OF_REQUESTS);
        System.out.println("Completed requests: " + completedRequests.get());
        System.out.println("Successful requests (HTTP 2xx): " + successfulRequests.get());
        System.out.println("Failed requests (non-2xx or exception): " + failedRequests.get());
        System.out.println("Total duration: " + totalDurationMs + " ms");

        if (successfulRequests.get() > 0) {
            // Sort response times for percentile calculation
            Collections.sort(successfulResponseTimes);

            double averageResponseTime = (double) successfulResponseTimes.stream().mapToLong(Long::longValue).sum() / successfulRequests.get();
            System.out.printf("Average successful response time: %.2f ms\n", averageResponseTime);
            System.out.printf("Median (P50) response time: %.2f ms\n", calculatePercentile(successfulResponseTimes, 50.0));
            System.out.printf("90th Percentile (P90) response time: %.2f ms\n", calculatePercentile(successfulResponseTimes, 90.0));
            System.out.printf("95th Percentile (P95) response time: %.2f ms\n", calculatePercentile(successfulResponseTimes, 95.0));

        } else {
            System.out.println("No successful requests to calculate average and percentile response times.");
        }

        double requestsPerSecond = (double) completedRequests.get() / (totalDurationMs / 1000.0);
        System.out.printf("Overall Throughput: %.2f requests/second\n", requestsPerSecond);
        System.out.println("--------------------------------------------------");
    }

    private static void sendRequest(int requestNumber) {
        long startTime = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TARGET_URL))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            long endTime = System.nanoTime();
            long responseTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

            int statusCode = response.statusCode();
            String statusMessage = (statusCode >= 200 && statusCode < 300) ? "SUCCESS" : "FAILED";

            System.out.printf("Request #%d - Status: %d (%s), Response Time: %d ms\n",
                    requestNumber, statusCode, statusMessage, responseTimeMs);

            completedRequests.incrementAndGet();
            if (statusCode >= 200 && statusCode < 300) {
                successfulRequests.incrementAndGet();
                successfulResponseTimes.add(responseTimeMs);
            } else {
                failedRequests.incrementAndGet();
                errorLogger.printf("Request #%d - FAILED (Status: %d): %s\n", requestNumber, statusCode, response.body());
            }

        } catch (IOException | InterruptedException e) {
            long endTime = System.nanoTime();
            long responseTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            System.err.printf("Request #%d - Error: %s, Response Time: %d ms\n",
                    requestNumber, e.getMessage(), responseTimeMs);
            errorLogger.printf("Request #%d - ERROR: %s, Response Time: %d ms\n", requestNumber, e.getMessage(), responseTimeMs);
            completedRequests.incrementAndGet();
            failedRequests.incrementAndGet();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
        }
    }

    /**
     * Calculates the specified percentile from a list of sorted latencies.
     *
     * @param latencies The list of response times in milliseconds, assumed to be sorted.
     * @param percentile The percentile to calculate (e.g., 90.0 for 90th percentile).
     * @return The calculated percentile value.
     */
    private static double calculatePercentile(List<Long> latencies, double percentile) {
        if (latencies.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * latencies.size()) - 1;
        if (index < 0) { // Handle case where percentile is very small and rounds down
            index = 0;
        } else if (index >= latencies.size()) { // Handle case where percentile is 100 or higher
            index = latencies.size() - 1;
        }
        return latencies.get(index);
    }
}
