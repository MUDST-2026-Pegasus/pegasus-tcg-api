package com.pegasus.pegasustcgapi.storage;

/**
 * What storage says about an object that is actually there. A feature checks this
 * before saving the key, because a presigned URL only promises where a file may
 * be put — not what was put, or that anything was.
 */
public record StoredObject(String key, long sizeBytes, String contentType) {
}
