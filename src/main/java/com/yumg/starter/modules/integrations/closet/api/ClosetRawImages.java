package com.yumg.starter.modules.integrations.closet.api;

import com.yumg.starter.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Raw image bodies for try-on content: kept in memory only, never written to disk or logs. */
final class ClosetRawImages {
    private static final Set<String> ACCEPTED = Set.of("image/jpeg", "image/png", "image/webp");
    private ClosetRawImages() {}

    record Upload(byte[] bytes, MediaType type) {}

    static Upload read(HttpServletRequest request, int limit) throws IOException {
        MediaType type;
        try { type = MediaType.parseMediaType(request.getContentType()); }
        catch (RuntimeException exception) { throw ApiException.invalidParameter(); }
        MediaType bare = new MediaType(type.getType(), type.getSubtype());
        if (!ACCEPTED.contains(bare.toString())) throw ApiException.invalidParameter();
        if (request.getContentLengthLong() > limit) throw ClosetShowcaseController.tooLarge();
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw ClosetShowcaseController.tooLarge();
        if (bytes.length == 0) throw ApiException.invalidParameter();
        return new Upload(bytes, bare);
    }

    /** The upstream may store PNG or JPEG; label the bytes by their signature instead of guessing. */
    static ResponseEntity<byte[]> response(byte[] bytes) {
        return ResponseEntity.ok().contentType(sniff(bytes)).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    static MediaType sniff(byte[] b) {
        if (b == null) return MediaType.APPLICATION_OCTET_STREAM;
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return MediaType.IMAGE_PNG;
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return MediaType.IMAGE_JPEG;
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return MediaType.parseMediaType("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
