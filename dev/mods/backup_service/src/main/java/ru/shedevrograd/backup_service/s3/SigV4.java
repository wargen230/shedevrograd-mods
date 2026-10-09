package ru.shedevrograd.backup_service.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * AWS Signature Version 4 for S3 requests.
 * https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
 */
public final class SigV4 {
    public static final String EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private SigV4() {}

    /**
     * Value of the Authorization header.
     *
     * @param canonicalUri already URI-encoded path, e.g. {@code /bucket/backups/full-...sbk}
     * @param query        decoded query parameters
     * @param headers      every header to sign, including {@code host}, {@code x-amz-date} and
     *                     {@code x-amz-content-sha256}; names are matched case-insensitively
     */
    public static String authorization(String method, String canonicalUri, Map<String, String> query, Map<String, String> headers,
                                       String payloadSha256, ZonedDateTime time, String region, String accessKey, String secretKey) {
        SortedMap<String, String> canonicalHeaders = new TreeMap<>();
        headers.forEach((name, value) -> canonicalHeaders.put(name.toLowerCase(Locale.ROOT), value.trim().replaceAll(" +", " ")));
        String signedHeaders = String.join(";", canonicalHeaders.keySet());

        String canonicalRequest = method + "\n"
                + canonicalUri + "\n"
                + SigV4.canonicalQuery(query) + "\n"
                + canonicalHeaders.entrySet().stream().map(h -> h.getKey() + ":" + h.getValue() + "\n").collect(Collectors.joining()) + "\n"
                + signedHeaders + "\n"
                + payloadSha256;

        String date = SigV4.amzDate(time).substring(0, 8);
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + SigV4.amzDate(time) + "\n" + scope + "\n" + SigV4.sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] key = SigV4.hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = SigV4.hmac(key, region);
        key = SigV4.hmac(key, "s3");
        key = SigV4.hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(SigV4.hmac(key, stringToSign));

        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    public static String amzDate(ZonedDateTime time) {
        return time.withZoneSameInstant(ZoneOffset.UTC).format(AMZ_DATE);
    }

    /** Query string in the canonical form; also used as the actual request query so both always match. */
    public static String canonicalQuery(Map<String, String> query) {
        return new TreeMap<>(query).entrySet().stream()
                .map(p -> SigV4.encode(p.getKey(), true) + "=" + SigV4.encode(p.getValue(), true))
                .collect(Collectors.joining("&"));
    }

    /** RFC 3986 encoding as S3 expects: only unreserved characters stay, '/' kept in paths. */
    public static String encode(String value, boolean encodeSlash) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !encodeSlash)) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    public static String sha256Hex(byte[] data) {
        return SigV4.sha256Hex(data, 0, data.length);
    }

    public static String sha256Hex(byte[] data, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data, offset, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
