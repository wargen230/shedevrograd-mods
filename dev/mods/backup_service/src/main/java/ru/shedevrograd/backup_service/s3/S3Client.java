package ru.shedevrograd.backup_service.s3;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal S3 client (MinIO and other S3-compatible storages) on top of java.net.http.
 *
 * <p>Implements only what backups need: list, upload (single PUT or multipart), delete.
 * No SDK on purpose: the AWS SDK pulls in tens of megabytes of libraries that could clash with
 * other mods. Every request body is signed with its SHA-256, so the storage rejects corrupted uploads.
 */
public final class S3Client {
    /** Files up to this size go in one PUT, larger ones in parts of this size. S3 allows 5 MiB to 5 GiB per part. */
    public static final long DEFAULT_PART_SIZE = 32L * 1024 * 1024;
    private static final long MIN_PART_SIZE = 5L * 1024 * 1024;
    private static final int MAX_PARTS = 10_000;

    public record Settings(URI endpoint, String region, String bucket, String accessKey, String secretKey, boolean pathStyle) {}

    public record ObjectInfo(String key, long size) {}

    private final Settings settings;
    private final long partSize;
    private final HttpClient http;

    public S3Client(Settings settings) {
        this(settings, DEFAULT_PART_SIZE);
    }

    public S3Client(Settings settings, long partSize) {
        if (partSize < MIN_PART_SIZE) {
            throw new IllegalArgumentException("S3 part size must be at least 5 MiB");
        }
        this.settings = settings;
        this.partSize = partSize;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** All objects whose key starts with {@code prefix}. */
    public List<ObjectInfo> list(String prefix) throws IOException {
        List<ObjectInfo> objects = new ArrayList<>();
        String continuation = null;
        do {
            Map<String, String> query = new HashMap<>();
            query.put("list-type", "2");
            query.put("prefix", prefix);
            if (continuation != null) {
                query.put("continuation-token", continuation);
            }

            Document xml = S3Client.parseXml(this.send("GET", "", query, new byte[0]).body());
            NodeList contents = xml.getElementsByTagName("Contents");
            for (int i = 0; i < contents.getLength(); i++) {
                Element item = (Element) contents.item(i);
                objects.add(new ObjectInfo(S3Client.text(item, "Key"), Long.parseLong(S3Client.text(item, "Size"))));
            }
            continuation = "true".equals(S3Client.text(xml.getDocumentElement(), "IsTruncated"))
                    ? S3Client.text(xml.getDocumentElement(), "NextContinuationToken")
                    : null;
        } while (continuation != null);
        return objects;
    }

    /** Uploads a file. The object becomes visible only once it is complete, never half-written. */
    public void upload(String key, Path file) throws IOException {
        long size = Files.size(file);
        if (size <= this.partSize) {
            this.send("PUT", key, Map.of(), Files.readAllBytes(file));
            return;
        }

        long parts = (size + this.partSize - 1) / this.partSize;
        if (parts > MAX_PARTS) {
            throw new IOException(file.getFileName() + " is too large for " + MAX_PARTS + " parts of " + this.partSize + " bytes");
        }

        String uploadId = S3Client.text(S3Client.parseXml(this.send("POST", key, Map.of("uploads", ""), new byte[0]).body()).getDocumentElement(), "UploadId");
        try {
            List<String> etags = new ArrayList<>();
            byte[] buffer = new byte[(int) this.partSize];
            try (InputStream in = Files.newInputStream(file)) {
                for (int part = 1; part <= parts; part++) {
                    int read = in.readNBytes(buffer, 0, buffer.length);
                    byte[] body = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
                    HttpResponse<byte[]> response = this.send("PUT", key,
                            Map.of("partNumber", Integer.toString(part), "uploadId", uploadId), body);
                    etags.add(response.headers().firstValue("ETag")
                            .orElseThrow(() -> new IOException("S3 did not return an ETag for a part")));
                }
            }

            StringBuilder complete = new StringBuilder("<CompleteMultipartUpload>");
            for (int i = 0; i < etags.size(); i++) {
                complete.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
                        .append(etags.get(i).replace("&", "&amp;").replace("\"", "&quot;"))
                        .append("</ETag></Part>");
            }
            complete.append("</CompleteMultipartUpload>");
            HttpResponse<byte[]> response = this.send("POST", key, Map.of("uploadId", uploadId),
                    complete.toString().getBytes(StandardCharsets.UTF_8));
            // CompleteMultipartUpload may report an error with status 200
            S3Client.throwIfError(response);
        } catch (IOException | RuntimeException e) {
            try {
                this.send("DELETE", key, Map.of("uploadId", uploadId), new byte[0]);
            } catch (IOException abortFailed) {
                e.addSuppressed(abortFailed);
            }
            throw e;
        }
    }

    public void delete(String key) throws IOException {
        this.send("DELETE", key, Map.of(), new byte[0]);
    }

    private HttpResponse<byte[]> send(String method, String key, Map<String, String> query, byte[] body) throws IOException {
        URI endpoint = this.settings.endpoint();
        String host = endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        String path;
        if (this.settings.pathStyle()) {
            path = "/" + SigV4.encode(this.settings.bucket(), true) + (key.isEmpty() ? "" : "/" + SigV4.encode(key, false));
        } else {
            host = this.settings.bucket() + "." + host;
            path = "/" + SigV4.encode(key, false);
        }
        String queryString = SigV4.canonicalQuery(query);
        URI uri = URI.create(endpoint.getScheme() + "://" + host + path + (queryString.isEmpty() ? "" : "?" + queryString));

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String payloadHash = SigV4.sha256Hex(body);
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("host", host);
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", SigV4.amzDate(now));
        String authorization = SigV4.authorization(method, path, query, signed, payloadHash, now,
                this.settings.region(), this.settings.accessKey(), this.settings.secretKey());

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", SigV4.amzDate(now))
                .header("Authorization", authorization)
                .method(method, body.length == 0 && !method.equals("PUT")
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = this.http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new S3Exception(method + " " + path, response.statusCode(), S3Client.errorText(response.body()));
        }
        return response;
    }

    private static void throwIfError(HttpResponse<byte[]> response) throws IOException {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (body.contains("<Error>")) {
            throw new S3Exception("CompleteMultipartUpload", response.statusCode(), S3Client.errorText(response.body()));
        }
    }

    private static String errorText(byte[] body) {
        try {
            Element error = S3Client.parseXml(body).getDocumentElement();
            return S3Client.text(error, "Code") + ": " + S3Client.text(error, "Message");
        } catch (IOException | RuntimeException e) {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static Document parseXml(byte[] body) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // the response comes over the network: no DTDs, no external entities
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (Exception e) {
            throw new IOException("Unreadable S3 response: " + new String(body, StandardCharsets.UTF_8), e);
        }
    }

    private static String text(Element parent, String tag) {
        return Optional.ofNullable(parent.getElementsByTagName(tag).item(0))
                .map(node -> node.getTextContent())
                .orElse("");
    }

    public static final class S3Exception extends IOException {
        private final int status;

        public S3Exception(String request, int status, String detail) {
            super(request + " → HTTP " + status + " " + detail);
            this.status = status;
        }

        public int status() {
            return this.status;
        }
    }
}
