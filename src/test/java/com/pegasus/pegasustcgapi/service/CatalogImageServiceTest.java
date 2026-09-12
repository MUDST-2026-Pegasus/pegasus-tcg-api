package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.dto.CatalogImageResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CatalogImage;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository;
import com.pegasus.pegasustcgapi.repository.CatalogImageRepository.ImageFields;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.StoredObject;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogImageServiceTest {

    private static final long PRODUCT_ID = 501L;
    private static final String KEY = "catalog/2026/09/abc.png";

    @Mock
    private CatalogImageRepository images;

    @Mock
    private CatalogProductService products;

    @Mock
    private CatalogVariantService variants;

    @Mock
    private StorageService storage;

    private CatalogImageService service;

    @BeforeEach
    void setUp() {
        service = new CatalogImageService(images, products, variants, storage);
    }

    private static CatalogImage image(long id, boolean primary) {
        return new CatalogImage(id, PRODUCT_ID, null, KEY, "Pikachu ex", (short) 0, primary);
    }

    private static ImageFields fields(boolean primary) {
        return new ImageFields(null, KEY, "Pikachu ex", (short) 0, primary);
    }

    @Test
    @DisplayName("the first image of a product becomes its primary one")
    void firstImageIsPrimary() {
        given(storage.requireUploaded(KEY)).willReturn(new StoredObject(KEY, 1000, "image/png"));
        given(images.hasAny(PRODUCT_ID)).willReturn(false);
        given(images.insert(eq(PRODUCT_ID), any())).willReturn(1L);
        given(images.findById(1L)).willReturn(Optional.of(image(1L, true)));
        given(storage.presignDownload(KEY)).willReturn("http://localhost:9000/signed");

        CatalogImageResponse added = service.add(PRODUCT_ID, fields(false));

        ArgumentCaptor<ImageFields> saved = ArgumentCaptor.forClass(ImageFields.class);
        verify(images).insert(eq(PRODUCT_ID), saved.capture());
        assertThat(saved.getValue().primary()).isTrue();
        assertThat(added.url()).isEqualTo("http://localhost:9000/signed");
    }

    @Test
    @DisplayName("a key nothing was uploaded to is refused instead of stored as a broken image")
    void requiresTheUploadToHaveHappened() {
        willThrow(new NotFoundException(ErrorCode.FILE_NOT_FOUND))
                .given(storage).requireUploaded(KEY);

        assertThatThrownBy(() -> service.add(PRODUCT_ID, fields(false)))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);

        verify(images, never()).insert(anyLong(), any());
    }

    @Test
    @DisplayName("promoting an image demotes the one that held the flag")
    void primaryIsExclusive() {
        given(images.findById(2L)).willReturn(Optional.of(image(2L, false)));
        given(storage.presignDownload(KEY)).willReturn("http://localhost:9000/signed");

        service.makePrimary(PRODUCT_ID, 2L);

        verify(images).clearPrimary(PRODUCT_ID, 2L);
        verify(images).markPrimary(2L);
    }

    @Test
    @DisplayName("deleting the primary image promotes the next one and removes the file")
    void deletingPrimaryPromotesTheNext() {
        given(images.findById(1L)).willReturn(Optional.of(image(1L, true)));
        given(images.findByProductId(PRODUCT_ID)).willReturn(List.of(image(2L, false)));

        service.delete(PRODUCT_ID, 1L);

        verify(images).delete(1L);
        verify(images).markPrimary(2L);
        verify(storage).delete(KEY);
    }

    @Test
    @DisplayName("deleting the last image leaves nothing to promote")
    void deletingLastImage() {
        given(images.findById(1L)).willReturn(Optional.of(image(1L, true)));
        given(images.findByProductId(PRODUCT_ID)).willReturn(List.of());

        service.delete(PRODUCT_ID, 1L);

        verify(images).delete(1L);
        verify(images, never()).markPrimary(anyLong());
        verify(storage).delete(KEY);
    }

    @Test
    @DisplayName("an image belonging to another product is not found through this one")
    void imageOfAnotherProductIsNotFound() {
        CatalogImage otherProduct = new CatalogImage(9L, 777L, null, KEY, null, (short) 0, false);
        given(images.findById(9L)).willReturn(Optional.of(otherProduct));

        assertThatThrownBy(() -> service.delete(PRODUCT_ID, 9L))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.IMAGE_NOT_FOUND);

        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("art specific to one printing must be a printing of this product")
    void variantMustBelongToTheProduct() {
        willThrow(new NotFoundException(ErrorCode.VARIANT_NOT_FOUND))
                .given(variants).requireOfProduct(PRODUCT_ID, 902L);

        ImageFields ofAnotherVariant = new ImageFields(902L, KEY, null, (short) 0, false);

        assertThatThrownBy(() -> service.add(PRODUCT_ID, ofAnotherVariant))
                .isInstanceOf(NotFoundException.class);

        verify(storage, never()).requireUploaded(anyString());
    }
}
