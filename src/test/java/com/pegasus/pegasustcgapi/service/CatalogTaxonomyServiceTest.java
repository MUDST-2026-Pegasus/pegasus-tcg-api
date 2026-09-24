package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.CardSet;
import com.pegasus.pegasustcgapi.model.CatalogCategory;
import com.pegasus.pegasustcgapi.repository.CardSetRepository;
import com.pegasus.pegasustcgapi.repository.CardSetRepository.CardSetFields;
import com.pegasus.pegasustcgapi.repository.CatalogCategoryRepository;
import com.pegasus.pegasustcgapi.repository.CatalogCategoryRepository.CategoryFields;
import com.pegasus.pegasustcgapi.storage.StorageService;
import com.pegasus.pegasustcgapi.storage.UploadPurpose;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogTaxonomyServiceTest {

    private static final short POKEMON = 1;

    @Mock
    private CatalogCategoryRepository categories;

    @Mock
    private CardSetRepository cardSets;

    @Mock
    private GameService games;

    @Mock
    private StorageService storage;

    private CatalogTaxonomyService service;

    @BeforeEach
    void setUp() {
        service = new CatalogTaxonomyService(categories, cardSets, games, storage);
    }

    private static CatalogCategory singles() {
        return new CatalogCategory(201, POKEMON, null, "SINGLES", "Single cards",
                "single-cards", (short) 1, true, null);
    }

    private static CardSet terastal() {
        return new CardSet(301, POKEMON, "SV8A", "Terastal Festival", null,
                LocalDate.of(2024, 10, 18), 187, null);
    }

    @Test
    @DisplayName("a new category gets a folded code and a slug from its name")
    void createCategoryNormalises() {
        given(categories.codeTaken(POKEMON, "SINGLES", null)).willReturn(false);
        given(categories.insert(any())).willReturn(201);
        given(categories.findById(201)).willReturn(Optional.of(singles()));

        service.createCategory(new CategoryFields(POKEMON, null, " singles ", " Single cards ",
                null, (short) 1, true, null));

        ArgumentCaptor<CategoryFields> saved = ArgumentCaptor.forClass(CategoryFields.class);
        verify(categories).insert(saved.capture());
        assertThat(saved.getValue().code()).isEqualTo("SINGLES");
        assertThat(saved.getValue().slug()).isEqualTo("single-cards");
    }

    @Test
    @DisplayName("a cross-game category needs no game, and the game is never checked")
    void crossGameCategorySkipsGameLookup() {
        given(categories.codeTaken(null, "ACCESSORY", null)).willReturn(false);
        given(categories.insert(any())).willReturn(203);
        given(categories.findById(203)).willReturn(Optional.of(
                new CatalogCategory(203, null, null, "ACCESSORY", "Accessories",
                        "accessories", (short) 9, true, null)));

        CatalogCategory created = service.createCategory(new CategoryFields(null, null,
                "ACCESSORY", "Accessories", null, (short) 9, true, null));

        assertThat(created.isCrossGame()).isTrue();
        verify(games, never()).require(anyShort());
    }

    @Test
    @DisplayName("a code already used on that shelf is a conflict")
    void duplicateCategoryCodeIsRejected() {
        given(categories.codeTaken(POKEMON, "SINGLES", null)).willReturn(true);

        assertThatThrownBy(() -> service.createCategory(new CategoryFields(POKEMON, null,
                "SINGLES", "Single cards", null, (short) 1, true, null)))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ErrorCode.CATEGORY_CODE_ALREADY_USED);

        verify(categories, never()).insert(any());
    }

    @Test
    @DisplayName("a category cannot be filed under itself")
    void categoryCannotParentItself() {
        given(categories.findById(201)).willReturn(Optional.of(singles()));

        assertThatThrownBy(() -> service.updateCategory(201, new CategoryFields(POKEMON, 201,
                "SINGLES", "Single cards", null, (short) 1, true, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a parent that does not exist is 404")
    void unknownParentIsRejected() {
        given(categories.findById(999)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createCategory(new CategoryFields(null, 999,
                "SUB", "Sub", null, (short) 0, true, null)))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    @DisplayName("a parent from another game is refused, since that game's tree would not show it")
    void parentOfAnotherGameIsRejected() {
        CatalogCategory magicSingles = new CatalogCategory(301, (short) 2, null, "SINGLES",
                "Single cards", "magic-single-cards", (short) 1, true, null);
        given(categories.findById(301)).willReturn(Optional.of(magicSingles));

        assertThatThrownBy(() -> service.createCategory(new CategoryFields(POKEMON, 301,
                "PROMO", "Promos", null, (short) 0, true, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("same game");

        verify(categories, never()).insert(any());
    }

    @Test
    @DisplayName("a cross-game category cannot sit under one game's category")
    void crossGameChildOfAGameCategoryIsRejected() {
        given(categories.findById(201)).willReturn(Optional.of(singles()));

        assertThatThrownBy(() -> service.createCategory(new CategoryFields(null, 201,
                "SLEEVES", "Sleeves", null, (short) 0, true, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("same game");
    }

    @Test
    @DisplayName("a game's category may sit under a cross-game one")
    void crossGameParentIsAllowed() {
        CatalogCategory accessories = new CatalogCategory(203, null, null, "ACCESSORY",
                "Accessories", "accessories", (short) 9, true, null);
        given(categories.findById(203)).willReturn(Optional.of(accessories));
        given(categories.codeTaken(POKEMON, "PLAYMATS", null)).willReturn(false);
        given(categories.insert(any())).willReturn(204);
        given(categories.findById(204)).willReturn(Optional.of(new CatalogCategory(204, POKEMON, 203,
                "PLAYMATS", "Playmats", "playmats", (short) 0, true, null)));

        CatalogCategory created = service.createCategory(new CategoryFields(POKEMON, 203,
                "PLAYMATS", "Playmats", null, (short) 0, true, null));

        assertThat(created.parentId()).isEqualTo(203);
    }

    @Test
    @DisplayName("editing a category keeps the slug it was created with")
    void updateCategoryKeepsSlug() {
        given(categories.findById(201)).willReturn(Optional.of(singles()));
        given(categories.codeTaken(POKEMON, "SINGLES", 201)).willReturn(false);

        service.updateCategory(201, new CategoryFields(POKEMON, null, "SINGLES",
                "Singles (renamed)", "a-new-slug", (short) 2, false, null));

        ArgumentCaptor<CategoryFields> saved = ArgumentCaptor.forClass(CategoryFields.class);
        verify(categories).update(eq(201), saved.capture());
        assertThat(saved.getValue().slug()).isEqualTo("single-cards");
        assertThat(saved.getValue().active()).isFalse();
    }

    @Test
    @DisplayName("a set code is folded and unique within its game")
    void cardSetCodeIsFolded() {
        given(cardSets.codeTaken(POKEMON, "SV8A", null)).willReturn(false);
        given(cardSets.insert(eq(POKEMON), any())).willReturn(301);
        given(cardSets.findById(301)).willReturn(Optional.of(terastal()));

        service.createCardSet(POKEMON, new CardSetFields(" sv8a ", " Terastal Festival ", null,
                LocalDate.of(2024, 10, 18), 187, null));

        ArgumentCaptor<CardSetFields> saved = ArgumentCaptor.forClass(CardSetFields.class);
        verify(cardSets).insert(eq(POKEMON), saved.capture());
        assertThat(saved.getValue().code()).isEqualTo("SV8A");
        assertThat(saved.getValue().name()).isEqualTo("Terastal Festival");
    }

    @Test
    @DisplayName("a duplicate set code in the same game is a conflict")
    void duplicateCardSetCodeIsRejected() {
        given(cardSets.codeTaken(POKEMON, "SV8A", null)).willReturn(true);

        assertThatThrownBy(() -> service.createCardSet(POKEMON,
                new CardSetFields("SV8A", "Terastal Festival", null, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ErrorCode.CARD_SET_CODE_ALREADY_USED);

        verify(cardSets, never()).insert(anyShort(), any());
    }

    @Test
    @DisplayName("an unknown set is 404 rather than a silent no-op update")
    void updateUnknownCardSet() {
        given(cardSets.findById(999)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCardSet(999,
                new CardSetFields("SV8A", "Terastal Festival", null, null, null, null)))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.CARD_SET_NOT_FOUND);

        verify(cardSets, never()).update(anyInt(), any());
    }

    @Test
    @DisplayName("a new tile picture has to be a finished catalogue upload, so a private file cannot go public")
    void newImageMustBeACatalogUpload() {
        String bankBook = "verifications/2026/09/bank-book.jpg";
        given(storage.requireUploadedFor(UploadPurpose.CATALOG_IMAGE, bankBook))
                .willThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "The key must come from a CATALOG_IMAGE upload"));

        assertThatThrownBy(() -> service.createCategory(new CategoryFields(null, null,
                "BOXES", "Boxes", null, (short) 0, true, bankBook)))
                .isInstanceOf(ApiException.class);

        verify(categories, never()).insert(any());
    }

    @Test
    @DisplayName("keeping the picture a category already has needs no new upload")
    void unchangedImageIsNotRechecked() {
        given(categories.findById(201)).willReturn(Optional.of(new CatalogCategory(201, POKEMON, null,
                "SINGLES", "Single cards", "single-cards", (short) 1, true, "seed/categories/singles.jpg")));
        given(categories.codeTaken(POKEMON, "SINGLES", 201)).willReturn(false);

        service.updateCategory(201, new CategoryFields(POKEMON, null, "SINGLES", "Singles", null,
                (short) 1, true, "seed/categories/singles.jpg"));

        verifyNoInteractions(storage);
    }
}
