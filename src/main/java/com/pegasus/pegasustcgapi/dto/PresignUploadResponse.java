package com.pegasus.pegasustcgapi.dto;

/**
 * Permission to upload one file, for a few minutes.
 *
 * <p>The client PUTs the file straight to {@code uploadUrl}, then sends
 * {@code objectKey} back to whichever endpoint owns the record — the catalogue
 * image, the payment slip, the return evidence. The key is what gets stored; the
 * URL is deliberately not, since it expires.
 *
 * @param maxBytes repeated here so a client can show the limit without hard-coding it
 */
public record PresignUploadResponse(
        String objectKey,
        String uploadUrl,
        long expiresInSeconds,
        long maxBytes) {
}
