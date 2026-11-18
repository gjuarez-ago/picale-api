import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

/** Explicitly invoked, isolated-prefix R2 probe. Never starts Spring or edits originals.
 * Manifest contains temporary bearer URLs: keep only in ignored build/target folders.
 */
public class SubidasR2Probe {
    static final ObjectMapper JSON = new ObjectMapper();
    static final long PART = 5L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("open|verify|cleanup manifest [source-file]");
        String operation = args[0];
        Path manifest = Path.of(args[1]).toAbsolutePath().normalize();
        Map<String, String> config = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(".env"))) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains("=")) continue;
            int separator = trimmed.indexOf('=');
            String value = trimmed.substring(separator + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
            config.put(trimmed.substring(0, separator), value);
        }
        String bucket = required(config, "CLOUDFLARE_R2_BUCKET_NAME");
        var endpoint = java.net.URI.create(required(config, "CLOUDFLARE_R2_ENDPOINT"));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                required(config, "CLOUDFLARE_R2_ACCESS_KEY_ID"), required(config, "CLOUDFLARE_R2_SECRET_ACCESS_KEY")));
        try (var client = S3Client.builder().endpointOverride(endpoint).region(Region.of("auto"))
                    .credentialsProvider(credentials).build();
             var signer = S3Presigner.builder().endpointOverride(endpoint).region(Region.of("auto"))
                    .credentialsProvider(credentials).build()) {
            if (operation.equals("open")) {
                if (Files.exists(manifest)) throw new IllegalStateException("Manifest already exists; recover or clean it first");
                Path source = Path.of(args[2]).toRealPath();
                long size = Files.size(source);
                if (size < 2 * PART || size > 50L * 1024 * 1024) throw new IllegalArgumentException("Use local video between 10 and 50 MiB");
                String run = UUID.randomUUID().toString();
                String prefix = "pruebas/subidas-background/" + run + "/";
                String fullKey = prefix + "full.mp4", multipartKey = prefix + "multipart.mp4";
                Map<String, Object> data = new HashMap<>();
                data.put("runId", run); data.put("prefix", prefix); data.put("bucket", bucket);
                data.put("filePath", source.toString()); data.put("sizeBytes", size);
                data.put("partSizeBytes", PART); data.put("fullKey", fullKey); data.put("multipartKey", multipartKey);
                data.put("sha256", digest(Files.newInputStream(source)));
                Files.createDirectories(manifest.getParent());
                JSON.writeValue(manifest.toFile(), data); // recovery identity before external mutation
                String upload = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                        .bucket(bucket).key(multipartKey).contentType("video/mp4").build()).uploadId();
                data.put("uploadId", upload);
                JSON.writeValue(manifest.toFile(), data); // persist immediately for cleanup
                data.put("fullUrl", signer.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(30)).putObjectRequest(PutObjectRequest.builder()
                                .bucket(bucket).key(fullKey).contentType("video/mp4").build()).build()).url().toString());
                var parts = new ArrayList<Map<String, Object>>();
                int count = (int) ((size + PART - 1) / PART);
                for (int number = 1; number <= count; number++) {
                    var request = UploadPartRequest.builder().bucket(bucket).key(multipartKey).uploadId(upload).partNumber(number).build();
                    parts.add(Map.of("number", number, "start", (number - 1) * PART,
                            "end", Math.min(number * PART, size) - 1,
                            "url", signer.presignUploadPart(UploadPartPresignRequest.builder()
                                    .signatureDuration(Duration.ofMinutes(30)).uploadPartRequest(request).build()).url().toString()));
                    if (number == 1) data.put("expiredUrl", signer.presignUploadPart(UploadPartPresignRequest.builder()
                            .signatureDuration(Duration.ofSeconds(1)).uploadPartRequest(request).build()).url().toString());
                }
                data.put("parts", parts);
                JSON.writeValue(manifest.toFile(), data);
                System.out.println("Probe opened: run=" + run + ", bytes=" + size + ", parts=" + count + "; URLs omitted");
            } else {
                JsonNode data = JSON.readTree(manifest.toFile());
                String run = data.path("runId").asText();
                UUID.fromString(run);
                String prefix = "pruebas/subidas-background/" + run + "/";
                if (!prefix.equals(data.path("prefix").asText()) || !bucket.equals(data.path("bucket").asText()))
                    throw new IllegalStateException("Probe identity/bucket mismatch");
                String full = prefix + "full.mp4", multipart = prefix + "multipart.mp4";
                if (!full.equals(data.path("fullKey").asText()) || !multipart.equals(data.path("multipartKey").asText()))
                    throw new IllegalStateException("Invalid exact cleanup targets");
                if (operation.equals("verify")) {
                    List<Part> uploaded = client.listParts(ListPartsRequest.builder().bucket(bucket).key(multipart)
                            .uploadId(data.path("uploadId").asText()).build()).parts();
                    int count = data.path("parts").size();
                    if (uploaded.size() != count) throw new IllegalStateException("Incorrect number of parts");
                    List<CompletedPart> complete = new ArrayList<>();
                    for (int n = 1; n <= count; n++) {
                        final int number = n;
                        Part p = uploaded.stream().filter(part -> part.partNumber() == number).findFirst().orElseThrow();
                        long expected = Math.min(PART, data.path("sizeBytes").asLong() - (number - 1) * PART);
                        if (p.size() != expected) throw new IllegalStateException("Wrong part length");
                        complete.add(CompletedPart.builder().partNumber(number).eTag(p.eTag()).build());
                    }
                    client.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(bucket).key(multipart)
                            .uploadId(data.path("uploadId").asText())
                            .multipartUpload(CompletedMultipartUpload.builder().parts(complete).build()).build());
                    for (String key : List.of(full, multipart)) {
                        var head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
                        if (head.contentLength() != data.path("sizeBytes").asLong()) throw new IllegalStateException("Wrong object length");
                        try (ResponseInputStream<GetObjectResponse> stream = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
                            if (!digest(stream).equals(data.path("sha256").asText())) throw new IllegalStateException("Object SHA-256 differs from local original");
                        }
                    }
                    System.out.println("R2 verified: exact part lengths and SHA-256 of both objects match local video; run=" + run);
                } else if (operation.equals("cleanup")) {
                    if (data.hasNonNull("uploadId")) {
                        try { client.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(multipart)
                                .uploadId(data.path("uploadId").asText()).build()); }
                        catch (S3Exception ex) { if (ex.statusCode() != 404) throw ex; }
                    }
                    // Recover createMultipartUpload -> manifest write interruptions.
                    // Only sessions for this exact run's multipart key are ours.
                    var dangling = client.listMultipartUploads(ListMultipartUploadsRequest.builder()
                            .bucket(bucket).prefix(prefix).build());
                    if (Boolean.TRUE.equals(dangling.isTruncated()))
                        throw new IllegalStateException("Unexpected truncated exact-run upload listing");
                    for (var session : dangling.uploads()) {
                        if (!multipart.equals(session.key()))
                            throw new IllegalStateException("Unexpected key under exact probe prefix");
                        try { client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                                .bucket(bucket).key(multipart).uploadId(session.uploadId()).build()); }
                        catch (S3Exception ex) { if (ex.statusCode() != 404) throw ex; }
                    }
                    for (String key : List.of(full, multipart)) client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
                    for (String key : List.of(full, multipart)) {
                        try { client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
                            throw new IllegalStateException("Object still present after cleanup");
                        } catch (S3Exception ex) { if (ex.statusCode() != 404) throw ex; }
                    }
                    var remaining = client.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).prefix(prefix).build());
                    if (!remaining.uploads().isEmpty()) throw new IllegalStateException("Multipart remains after cleanup");
                    System.out.println("R2 cleanup verified: both probe objects absent, zero multipart under exact run prefix=" + prefix);
                    // Remove bearer URLs after cleanup; retain audit identity and original metadata.
                    var sanitized = (com.fasterxml.jackson.databind.node.ObjectNode) data.deepCopy();
                    sanitized.remove(List.of("fullUrl", "expiredUrl", "parts"));
                    sanitized.put("cleaned", true);
                    JSON.writeValue(manifest.toFile(), sanitized);
                } else throw new IllegalArgumentException("Unknown operation");
            }
        }
    }

    static String required(Map<String, String> map, String key) {
        String value = map.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing configuration: " + key);
        return value;
    }

    static String digest(InputStream stream) throws Exception {
        try (stream) {
            var hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int n;
            while ((n = stream.read(buffer)) != -1) hash.update(buffer, 0, n);
            return HexFormat.of().formatHex(hash.digest());
        }
    }
}
