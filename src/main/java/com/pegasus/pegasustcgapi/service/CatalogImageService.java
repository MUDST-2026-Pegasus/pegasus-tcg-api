package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.dto.CatalogImageResponse;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CatalogImage;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository.ImageFields;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Official art for a catalogue entry [RQ-8].
 *
 * <p>The file itself never passes through here: the client uploads it with a
 * presigned URL and sends back the key. What this class does is confirm the
 * upload actually landed, and is an image this purpose allows, before the key is
 * stored — a presigned URL only promises where a file may be put, not what was
 * put or that anything was — and turn stored keys back into
 * short-lived URLs on the way out, so the bucket itself stays private.
 */
@Service
public class CatalogImageService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImageService.class);

    private final CatalogImageRepository images;
    private final CatalogProductService products;
    private final CatalogVariantService variants;
    private final StorageService storage;

    public CatalogImageService(
            CatalogImageRepository images,
            CatalogProductService products,
            CatalogVariantService variants,
            StorageService storage) {

        this.images = images;
        this.products = products;
        this.variants = variants;
        this.storage = storage;
    }

    /** @param includeInactive false is the public view, where a retired product has no art to show */
    public List<CatalogImageResponse> listOfProduct(long productId, boolean includeInactive) {
        if (includeInactive) {
            products.require(productId);
        } else {
            products.requireActive(productId);
        }
        return images.findByProductId(productId).stream()
                .map(image -> CatalogImageResponse.of(image, storage.presignDownload(image.imageKey())))
                .toList();
    }

    /**
     * @param fields {@code catalogVariantId} null means the art is shared by every
     *               variant, which is the usual case
     */
    @Transactional
    public CatalogImageResponse add(long productId, ImageFields fields) {
        products.require(productId);
        if (fields.catalogVariantId() != null) {
            variants.requireOfProduct(productId, fields.catalogVariantId());
        }

        String imageKey = fields.imageKey().trim();
        // Official art is served on a public page: only a finished CATALOG_IMAGE upload will do.
        storage.requireUploadedFor(UploadPurpose.CATALOG_IMAGE, imageKey);
        // Deleting an image deletes its file, so a second row on the same file would break.
        if (images.keyInUse(imageKey)) {
            throw new ConflictException(ErrorCode.IMAGE_KEY_IN_USE,
                    "This file is already attached to a catalogue image; upload it again to use it twice");
        }

        // The first image of a product is its primary one; somebody has to be.
        boolean primary = fields.primary() || !images.hasAny(productId);
        if (primary) {
            images.clearPrimary(productId, null);
        }

        long id = images.insert(productId, withPrimary(fields, primary));
        log.info("Added image {} to product {}", fields.imageKey(), productId);
        return require(productId, id);
    }

    @Transactional
    public CatalogImageResponse makePrimary(long productId, long imageId) {
        CatalogImage image = requireImage(productId, imageId);

        images.clearPrimary(productId, image.id());
        images.markPrimary(image.id());
        return require(productId, imageId);
    }

    /**
     * Removes the row and the object behind it.
     *
     * <p>Deleting the primary image promotes the next one, so a product with any
     * art at all always has one to show.
     */
    @Transactional
    public void delete(long productId, long imageId) {
        CatalogImage image = requireImage(productId, imageId);

        images.delete(imageId);
        if (image.primary()) {
            images.findByProductId(productId).stream()
                    .findFirst()
                    .ifPresent(next -> images.markPrimary(next.id()));
        }

        storage.delete(image.imageKey());
        log.info("Deleted image {} of product {}", image.imageKey(), productId);
    }

    private CatalogImageResponse require(long productId, long imageId) {
        CatalogImage image = requireImage(productId, imageId);
        return CatalogImageResponse.of(image, storage.presignDownload(image.imageKey()));
    }

    /** Read through the product, so an id belonging to another card is not found. */
    private CatalogImage requireImage(long productId, long imageId) {
        return images.findById(imageId)
                .filter(image -> image.catalogProductId() == productId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.IMAGE_NOT_FOUND));
    }

    private static ImageFields withPrimary(ImageFields fields, boolean primary) {
        return new ImageFields(fields.catalogVariantId(), fields.imageKey().trim(),
                fields.altText(), fields.sortOrder(), primary);
    }
}
