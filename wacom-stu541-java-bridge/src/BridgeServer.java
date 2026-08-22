import com.WacomGSS.STU.IOErrorException;
import com.WacomGSS.STU.NotConnectedException;
import com.WacomGSS.STU.TlsDevice;
import com.WacomGSS.STU.Protocol.Capability;
import com.WacomGSS.STU.Protocol.PenData;
import com.WacomGSS.STU.Protocol.PenDataTimeCountSequence;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class BridgeServer {
  private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("WACOM_BRIDGE_PORT", "8765"));
  private static final Set<String> ALLOWED_ORIGINS = allowedOrigins();
  private static final Object CAPTURE_LOCK = new Object();
  private static final Future<KeyPair> KEY_PAIR = createKeyPair();
  private static final int MAX_CAPTURE_ATTEMPTS = 3;
  private static final long DEVICE_RECONNECT_TIMEOUT_MS = 10_000L;

  public static void main(String[] args) throws Exception {
    Path webRoot = Path.of(System.getProperty("wacom.webRoot", "web")).toAbsolutePath().normalize();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
    server.createContext("/api/health", BridgeServer::health);
    server.createContext("/api/capture", BridgeServer::capture);
    server.createContext("/", exchange -> staticFile(exchange, webRoot));
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    System.out.println("Wacom STU-541 Bridge attivo su http://127.0.0.1:" + PORT);
    System.out.println("Origini web consentite: " + ALLOWED_ORIGINS);
  }

  private static void health(HttpExchange exchange) throws IOException {
    if (!prepare(exchange, "GET")) return;
    try {
      TlsDevice[] devices = TlsDevice.getTlsDevices();
      sendJson(exchange, 200, "{\"ok\":true,\"tlsDevices\":" + devices.length + ",\"busy\":false}");
    } catch (Throwable error) {
      sendError(exchange, 500, error);
    }
  }

  private static void capture(HttpExchange exchange) throws IOException {
    if (!prepare(exchange, "POST")) return;
    synchronized (CAPTURE_LOCK) {
      try {
        TlsDevice[] devices = TlsDevice.getTlsDevices();
        if (devices.length == 0) {
          sendJson(exchange, 404, "{\"ok\":false,\"error\":\"Nessuna tavoletta TLS rilevata\"}");
          return;
        }

        CaptureResult result = acquireWithRetry(devices[0]);
        if (result.cancelled) {
          sendJson(exchange, 409, "{\"ok\":false,\"cancelled\":true,\"error\":\"Acquisizione annullata\"}");
          return;
        }
        sendJson(exchange, 200, result.toJson());
      } catch (Throwable error) {
        error.printStackTrace();
        sendError(exchange, 500, error);
      }
    }
  }

  private static CaptureResult acquireWithRetry(TlsDevice firstDevice) throws Exception {
    TlsDevice device = firstDevice;
    Exception lastError = null;

    for (int attempt = 1; attempt <= MAX_CAPTURE_ATTEMPTS; attempt++) {
      try {
        return acquire(device);
      } catch (Exception error) {
        lastError = error;
        if (!isTransientTabletError(error) || attempt == MAX_CAPTURE_ATTEMPTS) {
          throw error;
        }

        System.err.println(
            "Errore TLS/USB temporaneo durante l'acquisizione (tentativo "
                + attempt + "/" + MAX_CAPTURE_ATTEMPTS + "). "
                + "Attendo il riavvio della STU-541...");

        sleepWithoutLosingInterrupt(1_500L * attempt);
        device = waitForTlsDevice(DEVICE_RECONNECT_TIMEOUT_MS);
        if (device == null) {
          throw new Exception(
              "La STU-541 non e' tornata disponibile dopo il riavvio",
              lastError);
        }
        System.err.println("STU-541 nuovamente rilevata: riprovo automaticamente.");
      }
    }
    throw lastError;
  }

  private static TlsDevice waitForTlsDevice(long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    Throwable lastEnumerationError = null;

    while (System.currentTimeMillis() < deadline) {
      try {
        TlsDevice[] devices = TlsDevice.getTlsDevices();
        if (devices != null && devices.length > 0) return devices[0];
      } catch (Throwable error) {
        lastEnumerationError = error;
      }
      sleepWithoutLosingInterrupt(500L);
    }

    if (lastEnumerationError != null) {
      System.err.println("Errore durante la nuova rilevazione della STU-541: " + lastEnumerationError);
    }
    return null;
  }

  private static boolean isTransientTabletError(Throwable error) {
    Throwable current = error;
    while (current != null) {
      if (current instanceof IOErrorException || current instanceof NotConnectedException) return true;
      current = current.getCause();
    }
    return false;
  }

  private static void sleepWithoutLosingInterrupt(long milliseconds) throws InterruptedException {
    try {
      Thread.sleep(milliseconds);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw error;
    }
  }

  private static CaptureResult acquire(TlsDevice device) throws Exception {
    final CaptureResult[] result = new CaptureResult[1];
    final Throwable[] failure = new Throwable[1];
    SwingUtilities.invokeAndWait(() -> {
      DemoButtons.SignatureDialog dialog = null;
      try {
        dialog = new DemoButtons.SignatureDialog(null, null, device, false, KEY_PAIR);
        dialog.setTitle("Firma grafometrica Wacom STU-541");
        dialog.setVisible(true);
        PenData[] points = dialog.getPenData();
        if (points == null) {
          result[0] = CaptureResult.cancelled();
        } else {
          result[0] = CaptureResult.of(points, dialog.getCapability());
        }
      } catch (Throwable error) {
        failure[0] = error;
      } finally {
        if (dialog != null) dialog.dispose();
      }
    });
    if (failure[0] != null) throw new Exception(failure[0]);
    return result[0];
  }

  private static boolean prepare(HttpExchange exchange, String method) throws IOException {
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    if (origin != null && !ALLOWED_ORIGINS.contains(origin)) {
      sendJson(exchange, 403, "{\"ok\":false,\"error\":\"Origine web non autorizzata\"}");
      return false;
    }
    if (origin != null) {
      Headers h = exchange.getResponseHeaders();
      h.set("Access-Control-Allow-Origin", origin);
      h.set("Vary", "Origin");
      h.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
      h.set("Access-Control-Allow-Headers", "Content-Type");
      h.set("Access-Control-Allow-Private-Network", "true");
    }
    if ("OPTIONS".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return false;
    }
    if (!method.equals(exchange.getRequestMethod())) {
      sendJson(exchange, 405, "{\"ok\":false,\"error\":\"Metodo non consentito\"}");
      return false;
    }
    return true;
  }

  private static void staticFile(HttpExchange exchange, Path webRoot) throws IOException {
    if (!prepare(exchange, "GET")) return;
    String requested = exchange.getRequestURI().getPath();
    if (requested.equals("/")) requested = "/index.html";
    Path file = webRoot.resolve(requested.substring(1)).normalize();
    if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
      sendJson(exchange, 404, "{\"ok\":false,\"error\":\"Risorsa non trovata\"}");
      return;
    }
    byte[] bytes = Files.readAllBytes(file);
    exchange.getResponseHeaders().set("Content-Type", requested.endsWith(".html") ? "text/html; charset=utf-8" : "application/octet-stream");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static void sendError(HttpExchange exchange, int status, Throwable error) throws IOException {
    String message = error.getMessage() == null ? error.toString() : error.getMessage();
    sendJson(exchange, status, "{\"ok\":false,\"error\":\"" + escape(message) + "\"}");
  }

  private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static Set<String> allowedOrigins() {
    Set<String> result = new HashSet<>(Arrays.asList(
        "http://127.0.0.1:" + PORT,
        "http://localhost:" + PORT,
        "http://localhost:8090",
        "http://127.0.0.1:8090",
        "https://www.etweb.cloud",
        "https://etweb.cloud",
        "https://www.gsdweb.cloud",
        "https://gsdweb.cloud"));
    String extra = System.getenv("WACOM_ALLOWED_ORIGINS");
    if (extra != null) for (String value : extra.split(",")) if (!value.isBlank()) result.add(value.trim());
    return result;
  }

  private static Future<KeyPair> createKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return CompletableFuture.completedFuture(generator.generateKeyPair());
    } catch (Exception error) {
      return CompletableFuture.failedFuture(error);
    }
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
  }

  private static final class CaptureResult {
    final boolean cancelled;
    final PenData[] points;
    final Capability capability;

    private CaptureResult(boolean cancelled, PenData[] points, Capability capability) {
      this.cancelled = cancelled;
      this.points = points;
      this.capability = capability;
    }

    static CaptureResult cancelled() { return new CaptureResult(true, null, null); }
    static CaptureResult of(PenData[] points, Capability capability) { return new CaptureResult(false, points, capability); }

    String toJson() {
      StringBuilder json = new StringBuilder(256 + points.length * 80);
      json.append("{\"ok\":true,\"capturedAt\":\"").append(Instant.now()).append("\",");
      json.append("\"tabletMaxX\":").append(capability.getTabletMaxX()).append(',');
      json.append("\"tabletMaxY\":").append(capability.getTabletMaxY()).append(',');
      json.append("\"screenWidth\":").append(capability.getScreenWidth()).append(',');
      json.append("\"screenHeight\":").append(capability.getScreenHeight()).append(',');
      json.append("\"points\":[");
      for (int i = 0; i < points.length; i++) {
        if (i > 0) json.append(',');
        PenData p = points[i];
        json.append("{\"index\":").append(i)
            .append(",\"x\":").append(p.getX())
            .append(",\"y\":").append(p.getY())
            .append(",\"pressure\":").append(p.getPressure())
            .append(",\"penDown\":").append(p.getSw() != 0);
        if (p instanceof PenDataTimeCountSequence) {
          PenDataTimeCountSequence timed = (PenDataTimeCountSequence)p;
          json.append(",\"timeCount\":").append(timed.getTimeCount())
              .append(",\"sequence\":").append(timed.getSequence());
        }
        json.append('}');
      }
      return json.append("]}").toString();
    }
  }
}
