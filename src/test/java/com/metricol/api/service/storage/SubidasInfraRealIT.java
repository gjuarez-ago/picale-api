package com.metricol.api.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.*;
import com.metricol.api.config.*;
import com.metricol.api.entity.*;
import com.metricol.api.enums.*;
import com.metricol.api.repository.*;
import com.metricol.api.service.media.MiniaturasEnSegundoPlano;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Opt-in: PostgreSQL privado + HTTP autenticado + R2 real. Nunca usa la BD productiva. */
@EnabledIfSystemProperty(named = "subidas.real", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("qa")
@Import(SubidasInfraRealIT.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SubidasInfraRealIT {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String RUN = UUID.randomUUID().toString();
    static final String PREFIX = "pruebas/subidas-background/" + RUN + "/d45/";
    static final Path MANIFEST = Path.of("target/subidas-d45/r2-" + RUN + ".json");
    static final Set<String> KEYS = new TreeSet<>();
    static final Map<String, Integer> CREATES = new HashMap<>();
    static final List<Map<String, Object>> EVENTS = new ArrayList<>();
    static String bucket;
    static final AtomicBoolean LOST = new AtomicBoolean(), ABORT_FAIL = new AtomicBoolean();
    @LocalServerPort int port;
    @Autowired WorkspaceRepository workspaces;
    @Autowired UserRepository users;
    @Autowired WorkspaceMemberRepository members;
    @Autowired PasswordEncoder encoder;
    @Autowired MediaAssetRepository assets;
    @Autowired MultipartRecoveryWorker worker;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;
    @Autowired R2Properties props;
    @Autowired R2StorageService storage;
    @Autowired @Qualifier("r2Client") S3Client s3;
    @MockitoBean MiniaturasEnSegundoPlano miniaturas;
    HttpClient http;
    UUID workspace;
    String token, otherToken;
    Path source;
    long bytes;
    String sha;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws IOException {
        if (!"jdbc:postgresql://127.0.0.1:55445/subidas_d45".equals(System.getProperty("spring.datasource.url")))
            throw new IllegalStateException("Only the private D45 database is permitted");
        Map<String, String> env = r2env();
        r.add("cloudflare.r2.endpoint", () -> env.get("CLOUDFLARE_R2_ENDPOINT"));
        r.add("cloudflare.r2.access-key", () -> env.get("CLOUDFLARE_R2_ACCESS_KEY_ID"));
        r.add("cloudflare.r2.secret-key", () -> env.get("CLOUDFLARE_R2_SECRET_ACCESS_KEY"));
        r.add("cloudflare.r2.bucket", () -> env.get("CLOUDFLARE_R2_BUCKET_NAME"));
        r.add("cloudflare.r2.public-url", () -> env.get("CLOUDFLARE_R2_PUBLIC_URL"));
        String localSecret = UUID.randomUUID() + "-" + UUID.randomUUID();
        r.add("jwt.secret", () -> localSecret);
        r.add("jwt.issuer", () -> "subidas-d45-isolated");
        r.add("server.address", () -> "127.0.0.1");
        r.add("app.cors.allowed-origins", () -> "http://127.0.0.1");
        r.add("app.base-url", () -> "http://127.0.0.1");
        r.add("app.web-url", () -> "http://127.0.0.1");
        r.add("app.media.multipart.modo", () -> "TODAS");
        r.add("app.media.multipart.tamano-parte-bytes", () -> 5 * 1024 * 1024);
        r.add("app.media.derivados-cleanup-cron", () -> "-");
        r.add("app.agente.enabled", () -> false);
        r.add("app.comentarios.enabled", () -> false);
        r.add("app.metricas.enabled", () -> false);
        for (String key : List.of("app.publishing.queue.poll-delay-ms", "app.publishing.queue.rescue-delay-ms",
                "app.scheduling.poll-delay-ms", "app.media.orphan-delay-ms", "app.media.rescate-delay-ms", "app.media.unused-delay-ms"))
            r.add(key, () -> 86400000);
        r.add("logging.level.root", () -> "WARN");
        r.add("logging.level.com.metricol.api", () -> "WARN");
        r.add("logging.level.org.hibernate.SQL", () -> "OFF");
    }

    @TestConfiguration static class Config {
        @Bean @Primary R2StorageService liveStorage(@Qualifier("r2Client") S3Client normal,
                S3Presigner signer, R2Properties props, @Qualifier("r2OpeningClient") S3Client opening) {
            return new R2StorageService(normal, signer, props, opening) {
                @Override public String claveNueva(UUID ws, String filename, String mime) {
                    String key = PREFIX + UUID.randomUUID() + ".mp4";
                    synchronized (KEYS) { KEYS.add(key); audit(); }
                    return key;
                }
                @Override public String iniciarPorPartes(String key, String mime) {
                    String id = super.iniciarPorPartes(key, mime);
                    synchronized (KEYS) { CREATES.merge(key, 1, Integer::sum); audit(); }
                    if (LOST.compareAndSet(true, false)) throw SdkClientException.create("D45 injected response loss");
                    return id;
                }
                @Override public void abortarPorPartes(String key, String uploadId) {
                    if (ABORT_FAIL.compareAndSet(true, false)) throw SdkClientException.create("D45 injected abort failure");
                    super.abortarPorPartes(key, uploadId);
                }
            };
        }
    }

    @BeforeAll void prepare() throws Exception {
        try (var c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            try (var st = c.createStatement(); var rows = st.executeQuery("select current_database(),inet_server_port()")) {
                rows.next(); assertThat(rows.getString(1)).isEqualTo("subidas_d45"); assertThat(rows.getInt(2)).isEqualTo(55445);
            }
        }
        source = Path.of(System.getProperty("subidas.source")).toRealPath();
        if (!source.startsWith(Path.of(System.getProperty("user.home"), "Downloads").toRealPath())) throw new IllegalStateException("Source outside Downloads");
        bytes = Files.size(source); if (bytes < 11 * 1024 * 1024 || bytes > 15 * 1024 * 1024) throw new IllegalStateException("Expected a three-part source");
        sha = digest(Files.newInputStream(source)); bucket = props.getBucket(); Files.createDirectories(MANIFEST.getParent()); audit();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).version(HttpClient.Version.HTTP_1_1).build();
        Workspace w = new Workspace(); w.setName("D45 isolated " + RUN); w.setMusicaVideos(false); w.setRecorteAuto(false); w.setSubtitulosAuto(false);
        w = workspaces.save(w); workspace = w.getId();
        token = user(w, "owner"); otherToken = user(w, "other");
        event("prepared", Map.of("sourceBytes", bytes, "sha256", sha, "database", "PostgreSQL", "authentication", "local-login"));
    }

    @Test @Order(1) void concurrentOpeningResumeServerCompletionAndOwnership() throws Exception {
        assertThat(api("GET", "/media/usage", null, null).statusCode()).isEqualTo(401);
        JsonNode usage = data(api("GET", "/media/usage", null, token), 200);
        assertThat(usage.path("multipartSessionVersion").asInt()).isEqualTo(2);
        assertThat(usage.path("multipartMobileReady").asBoolean()).isFalse();
        String client = "real_" + UUID.randomUUID(); Map<String, Object> request = request(client);
        var a = http.sendAsync(apiRequest("POST", "/media/multipart", request, token), HttpResponse.BodyHandlers.ofString());
        var b = http.sendAsync(apiRequest("POST", "/media/multipart", request, token), HttpResponse.BodyHandlers.ofString());
        String id = data(a.get(90, TimeUnit.SECONDS), 200).path("assetId").asText();
        assertThat(data(b.get(90, TimeUnit.SECONDS), 200).path("assetId").asText()).isEqualTo(id);
        MediaAsset row = row(client);
        assertThat(storage.aperturasPorClave(row.getStorageKey())).size().isEqualTo(1);
        synchronized (KEYS) { assertThat(CREATES.get(row.getStorageKey())).isEqualTo(1); }
        JsonNode signed = data(api("POST", "/media/" + id + "/multipart/parts", Map.of("partNumbers", List.of(1,2,3)), token), 200);
        put(signed.path("signed").get(0), part(0, 5 * 1024 * 1024));
        JsonNode partial = data(api("GET", "/media/" + id + "/multipart/parts", null, token), 200);
        assertThat(partial.path("uploaded").size()).isEqualTo(1);
        assertThat(api("GET", "/media/" + id + "/multipart/parts", null, otherToken).statusCode()).isEqualTo(404);
        for (int n = 1; n < 3; n++) put(signed.path("signed").get(n), part(n * 5L * 1024 * 1024, (int)Math.min(5L * 1024 * 1024, bytes - n * 5L * 1024 * 1024)));
        tenant(() -> new TransactionTemplate(manager).execute(s -> {
            MediaAsset actual = assets.findForUploadUpdate(row.getId()).orElseThrow(); actual.setUploadNextAttemptAt(Instant.now().minusSeconds(1)); return assets.save(actual);
        }));
        worker.recuperar(); // No petición Flutter de complete.
        assertThat(row(client).getStatus()).isEqualTo(MediaAssetStatus.READY);
        assertThat(data(api("GET", "/media/subidas/" + client, null, token), 200).path("estado").asText()).isEqualTo("READY");
        assertThat(api("POST", "/media/" + id + "/multipart/complete", null, token).statusCode()).isEqualTo(200);
        assertThat(api("DELETE", "/media/" + id + "/multipart", null, token).statusCode()).isEqualTo(409);
        assertThat(storage.consultar(row.getStorageKey()).sizeBytes()).isEqualTo(bytes);
        try (var body = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(row.getStorageKey()).build())) {
            assertThat(digest(body)).isEqualTo(sha);
        }
        verify(miniaturas, times(1)).sacar(row.getId(), workspace.toString());
        event("complete-and-sha256", Map.of("parts", 3, "readyProtected", true, "ownerIsolation", true));
    }

    @Test @Order(2) void cancelWhilePartIsBeingSent() throws Exception {
        String client = "flight_" + UUID.randomUUID(); String id = data(api("POST", "/media/multipart", request(client), token), 200).path("assetId").asText();
        JsonNode part = data(api("POST", "/media/" + id + "/multipart/parts", Map.of("partNumbers", List.of(1)), token), 200).path("signed").get(0);
        byte[] payload = part(0, 5 * 1024 * 1024); CountDownLatch paused = new CountDownLatch(1), resume = new CountDownLatch(1);
        HttpRequest.BodyPublisher delegate = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload) {
            boolean waited;
            @Override public synchronized int read(byte[] b, int off, int len) {
                if (pos >= 512 * 1024 && !waited) {
                    waited = true; paused.countDown();
                    try { if (!resume.await(40, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for cancellation"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted upload"); }
                }
                return super.read(b, off, Math.min(len, 64 * 1024));
            }
        });
        HttpRequest.BodyPublisher publisher = new HttpRequest.BodyPublisher() {
            public long contentLength() { return payload.length; }
            public void subscribe(java.util.concurrent.Flow.Subscriber<? super ByteBuffer> sub) { delegate.subscribe(sub); }
        };
        var sending = http.sendAsync(HttpRequest.newBuilder(URI.create(part.path("url").asText())).timeout(Duration.ofSeconds(90))
                .PUT(publisher).build(), HttpResponse.BodyHandlers.discarding());
        try {
            assertThat(paused.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(api("DELETE", "/media/" + id + "/multipart", null, token).statusCode()).isEqualTo(200);
        } finally { resume.countDown(); }
        String uploadResult;
        try { uploadResult = "http-" + sending.get(90, TimeUnit.SECONDS).statusCode(); }
        catch (Exception ex) { uploadResult = "transport-ended"; } // No URL firmada en la traza.
        MediaAsset row = row(client);
        assertThat(row.getUploadDecision()).isEqualTo(UploadDecision.CANCELLED);
        assertThat(storage.aperturasPorClave(row.getStorageKey())).isEmpty();
        assertThat(storage.consultar(row.getStorageKey())).isNull();
        event("cancel-in-flight", Map.of("uploadResult", uploadResult, "noObject", true, "noSession", true));
    }

    @Test @Order(3) void realCreateWithInjectedLostResponseRecoversSameSession() throws Exception {
        String client = "lost_" + UUID.randomUUID(); LOST.set(true);
        assertThat(api("POST", "/media/multipart", request(client), token).statusCode()).isGreaterThanOrEqualTo(400);
        MediaAsset pending = row(client); assertThat(pending.getUploadOpeningState()).isEqualTo("SENT");
        assertThat(pending.getMultipartUploadId()).isNull();
        JsonNode recovered = data(api("GET", "/media/subidas/" + client, null, token), 200);
        assertThat(recovered.path("assetId").asText()).isEqualTo(pending.getId().toString());
        assertThat(row(client).getUploadOpeningState()).isEqualTo("ATTACHED");
        synchronized (KEYS) { assertThat(CREATES.get(pending.getStorageKey())).isEqualTo(1); }
        assertThat(api("DELETE", "/media/" + pending.getId() + "/multipart", null, token).statusCode()).isEqualTo(200);
        event("lost-response-injected", Map.of("sameSession", true, "createCalls", 1));
    }

    @Test @Order(4) void injectedAbortFailureRetainsReservationThenRealAbortRecovers() throws Exception {
        String client = "abort_" + UUID.randomUUID(); String id = data(api("POST", "/media/multipart", request(client), token), 200).path("assetId").asText();
        ABORT_FAIL.set(true);
        assertThat(api("DELETE", "/media/" + id + "/multipart", null, token).statusCode()).isGreaterThanOrEqualTo(400);
        MediaAsset pending = row(client); assertThat(pending.getStatus()).isEqualTo(MediaAssetStatus.PENDING);
        assertThat(pending.getUploadDecision()).isEqualTo(UploadDecision.CANCELLING);
        assertThat(data(api("GET", "/media/usage", null, token), 200).path("usedBytes").asLong()).isEqualTo(2 * bytes);
        assertThat(storage.aperturasPorClave(pending.getStorageKey())).isNotEmpty();
        tenant(() -> new TransactionTemplate(manager).execute(s -> {
            MediaAsset a = assets.findForUploadUpdate(pending.getId()).orElseThrow(); a.setUploadNextAttemptAt(Instant.now().minusSeconds(1)); return assets.save(a);
        }));
        worker.recuperar();
        assertThat(row(client).getUploadDecision()).isEqualTo(UploadDecision.CANCELLED);
        assertThat(storage.aperturasPorClave(pending.getStorageKey())).isEmpty();
        assertThat(data(api("GET", "/media/usage", null, token), 200).path("usedBytes").asLong()).isEqualTo(bytes);
        event("abort-failure-injected", Map.of("reservationRetained", true, "recovered", true));
    }

    @AfterAll void finish() throws Exception {
        LOST.set(false); ABORT_FAIL.set(false);
        try { if (source != null) assertThat(digest(Files.newInputStream(source))).isEqualTo(sha); }
        finally { if (Files.exists(MANIFEST)) cleanup(MANIFEST); }
    }
    String user(Workspace ws, String suffix) throws Exception {
        String password = UUID.randomUUID() + "!aA9";
        User u = users.save(User.builder().name("D45 " + suffix).email("d45-" + RUN + "-" + suffix + "@example.invalid")
                .password(encoder.encode(password)).workspace(ws).role(Role.ADMIN).build());
        members.save(WorkspaceMember.builder().user(u).workspace(ws).role(Role.ADMIN).build());
        String bearer = data(api("POST", "/auth/login", Map.of("email", u.getEmail(), "password", password), null), 200).path("token").asText();
        assertThat(!bearer.isBlank()).isTrue(); return bearer;
    }
    Map<String,Object> request(String client) {
        return Map.of("uploadProtocolVersion", 2, "clientUploadId", client, "uploadBatchId", UUID.randomUUID().toString(),
                "uploadBatchIndex", 0, "fileName", "d45-source.mp4", "contentType", "video/mp4", "sizeBytes", bytes,
                "huellaArchivo", sha, "aunqueRepetido", true);
    }
    HttpRequest apiRequest(String method, String path, Object body, String bearer) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(90));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (body != null) b.header("Content-Type", "application/json");
        return b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }
    HttpResponse<String> api(String method, String path, Object body, String bearer) throws Exception { return http.send(apiRequest(method,path,body,bearer), HttpResponse.BodyHandlers.ofString()); }
    JsonNode data(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode envelope = JSON.readTree(response.body());
        assertThat(envelope.path("ok").asBoolean()).isTrue();
        assertThat(envelope.hasNonNull("result")).isTrue();
        return envelope.path("result");
    }
    void put(JsonNode signature, byte[] body) throws Exception {
        assertThat(http.send(HttpRequest.newBuilder(URI.create(signature.path("url").asText())).timeout(Duration.ofSeconds(90))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    }
    byte[] part(long offset, int length) throws IOException {
        byte[] data = new byte[length]; try (var file = FileChannel.open(source, StandardOpenOption.READ)) {
            file.position(offset); ByteBuffer buffer = ByteBuffer.wrap(data); while (buffer.hasRemaining()) if (file.read(buffer) < 0) throw new EOFException();
        } return data;
    }
    MediaAsset row(String client) { return tenant(() -> assets.findByClientUploadId(client).orElseThrow()); }
    <T> T tenant(Supplier<T> operation) { Object[] v = new Object[1]; TenantIdentifierResolver.comoTenant(workspace.toString(), () -> v[0] = operation.get()); @SuppressWarnings("unchecked") T out = (T)v[0]; return out; }
    static String digest(InputStream stream) throws Exception { try (stream) { var hash = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[65536]; int n; while ((n=stream.read(b)) >= 0) if(n>0) hash.update(b,0,n); return HexFormat.of().formatHex(hash.digest()); } }
    static void event(String name, Map<String,Object> values) { synchronized (KEYS) { var e = new LinkedHashMap<String,Object>(); e.put("name",name); e.putAll(values); EVENTS.add(e); audit(); } }
    static void audit() {
        try { Files.createDirectories(MANIFEST.getParent()); JSON.writeValue(MANIFEST.toFile(), Map.of("runId",RUN,"prefix",PREFIX,"bucket",bucket == null ? "" : bucket,"keys",new ArrayList<>(KEYS),"creates",new HashMap<>(CREATES),"events",new ArrayList<>(EVENTS))); }
        catch (IOException ex) { throw new IllegalStateException("Cannot persist safe R2 audit"); }
    }
    static Map<String,String> r2env() throws IOException {
        Set<String> allowed = Set.of("CLOUDFLARE_R2_ENDPOINT","CLOUDFLARE_R2_ACCESS_KEY_ID","CLOUDFLARE_R2_SECRET_ACCESS_KEY","CLOUDFLARE_R2_BUCKET_NAME","CLOUDFLARE_R2_PUBLIC_URL");
        Map<String,String> out = new HashMap<>();
        for(String line:Files.readAllLines(Path.of(".env"))) { int eq=line.indexOf('='); if(eq<0) continue; String key=line.substring(0,eq).trim(); if(!allowed.contains(key)) continue; String value=line.substring(eq+1).trim(); if(value.length()>1 && ((value.startsWith("\"")&&value.endsWith("\""))||(value.startsWith("'")&&value.endsWith("'")))) value=value.substring(1,value.length()-1); out.put(key,value); }
        for(String key:allowed) if(out.getOrDefault(key,"").isBlank()) throw new IllegalStateException("Missing R2 test configuration"); return out;
    }
    public static void main(String[] args) throws Exception { if(args.length!=2 || !args[0].equals("cleanup")) throw new IllegalArgumentException("cleanup manifest"); cleanup(Path.of(args[1])); }
    static void cleanup(Path manifest) throws Exception {
        JsonNode audit = JSON.readTree(manifest.toFile()); String run = audit.path("runId").asText(); UUID.fromString(run);
        String prefix = "pruebas/subidas-background/" + run + "/d45/";
        if(!prefix.equals(audit.path("prefix").asText())) throw new IllegalStateException("Invalid cleanup prefix");
        var env = r2env(); if(!env.get("CLOUDFLARE_R2_BUCKET_NAME").equals(audit.path("bucket").asText())) throw new IllegalStateException("Cleanup bucket mismatch");
        R2Properties p = new R2Properties(); p.setEndpoint(env.get("CLOUDFLARE_R2_ENDPOINT")); p.setAccessKey(env.get("CLOUDFLARE_R2_ACCESS_KEY_ID")); p.setSecretKey(env.get("CLOUDFLARE_R2_SECRET_ACCESS_KEY")); p.setBucket(env.get("CLOUDFLARE_R2_BUCKET_NAME")); p.setPublicUrl(env.get("CLOUDFLARE_R2_PUBLIC_URL"));
        R2Config config = new R2Config(); boolean clean = true;
        try(S3Client client=config.r2Client(p); S3Client opening=config.r2OpeningClient(p); S3Presigner signer=config.r2Presigner(p)) {
            R2StorageService storage = new R2StorageService(client, signer, p, opening);
            for(JsonNode item:audit.path("keys")) {
                String key=item.asText(); if(!key.startsWith(prefix) || !key.substring(prefix.length()).matches("[a-f0-9-]{36}\\.mp4")) throw new IllegalStateException("Invalid exact cleanup target");
                try { for(String id:storage.aperturasPorClave(key)) storage.abortarPorPartes(key,id); storage.deleteByKey(key); if(storage.consultar(key)!=null || !storage.aperturasPorClave(key).isEmpty()) clean=false; }
                catch(RuntimeException ex) { clean=false; }
            }
            var objects = client.listObjectsV2(ListObjectsV2Request.builder().bucket(p.getBucket()).prefix(prefix).build());
            var sessions = client.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(p.getBucket()).prefix(prefix).build());
            ((com.fasterxml.jackson.databind.node.ObjectNode)audit).put("objectsRemaining", objects.contents().size());
            ((com.fasterxml.jackson.databind.node.ObjectNode)audit).put("multipartRemaining", sessions.uploads().size());
            clean = clean && objects.contents().isEmpty() && sessions.uploads().isEmpty()
                    && !Boolean.TRUE.equals(objects.isTruncated()) && !Boolean.TRUE.equals(sessions.isTruncated());
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode)audit).put("cleaned",clean);
        JSON.writeValue(manifest.toFile(),audit);
        if(!clean) throw new IllegalStateException("R2 cleanup pending; retain manifest");
        System.out.println("D45 R2 cleanup verified for exact run keys; no bearer URLs logged");
    }
}
