package ru.shedevrograd.backup_service.s3;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SigV4Test {

    /** "Example: GET Object" from the AWS S3 SigV4 header authentication documentation. */
    @Test
    void awsDocumentationGetObjectExample() {
        String authorization = SigV4.authorization(
                "GET", "/test.txt", Map.of(),
                Map.of("Host", "examplebucket.s3.amazonaws.com",
                        "Range", "bytes=0-9",
                        "x-amz-content-sha256", SigV4.EMPTY_PAYLOAD_SHA256,
                        "x-amz-date", "20130524T000000Z"),
                SigV4.EMPTY_PAYLOAD_SHA256,
                ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC),
                "us-east-1", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");

        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                        + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
                authorization);
    }

    @Test
    void encodesLikeS3() {
        assertEquals("backups/full-2026-10-04_03-00-00.sbk", SigV4.encode("backups/full-2026-10-04_03-00-00.sbk", false));
        assertEquals("a%20b%2Fc%2B~%C3%A9", SigV4.encode("a b/c+~é", true));
        assertEquals("list-type=2&prefix=srv%2F", SigV4.canonicalQuery(Map.of("prefix", "srv/", "list-type", "2")));
    }
}
