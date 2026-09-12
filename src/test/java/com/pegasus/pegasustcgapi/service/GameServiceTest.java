package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.exception.ApiException;
import com.pegasus.pegasustcgapi.exception.ConflictException;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.AttributeDataType;
import com.pegasus.pegasustcgapi.model.Game;
import com.pegasus.pegasustcgapi.model.GameAttribute;
import com.pegasus.pegasustcgapi.repository.GameAttributeRepository;
import com.pegasus.pegasustcgapi.repository.GameAttributeRepository.AttributeFields;
import com.pegasus.pegasustcgapi.repository.GameRepository;
import com.pegasus.pegasustcgapi.repository.GameRepository.GameFields;
import java.time.OffsetDateTime;
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
class GameServiceTest {

    private static final short POKEMON = 1;

    @Mock
    private GameRepository games;

    @Mock
    private GameAttributeRepository attributes;

    private GameService service;

    @BeforeEach
    void setUp() {
        service = new GameService(games, attributes);
    }

    private static Game pokemon() {
        return new Game(POKEMON, "POKEMON", "Pokemon TCG", "โปเกมอนการ์ด", "pokemon-tcg",
                null, (short) 1, true, 9L, OffsetDateTime.now());
    }

    private static AttributeFields hp() {
        return new AttributeFields("hp", "HP", AttributeDataType.NUMBER, List.of(),
                true, false, (short) 1);
    }

    @Test
    @DisplayName("a new game gets a folded code and a slug built from its name")
    void createNormalisesCodeAndBuildsSlug() {
        given(games.codeTaken(anyString(), eq(null))).willReturn(false);
        given(games.slugTaken(anyString())).willReturn(false);
        given(games.insert(any(), eq(9L))).willReturn(POKEMON);
        given(games.findById(POKEMON)).willReturn(Optional.of(pokemon()));

        service.create(new GameFields(" pokemon ", " Pokemon TCG ", null, null, null, (short) 1, true), 9L);

        ArgumentCaptor<GameFields> saved = ArgumentCaptor.forClass(GameFields.class);
        verify(games).insert(saved.capture(), eq(9L));
        assertThat(saved.getValue().code()).isEqualTo("POKEMON");
        assertThat(saved.getValue().name()).isEqualTo("Pokemon TCG");
        assertThat(saved.getValue().slug()).isEqualTo("pokemon-tcg");
    }

    @Test
    @DisplayName("a code another game already holds is a conflict, not a second row")
    void duplicateCodeIsRejected() {
        given(games.codeTaken("POKEMON", null)).willReturn(true);

        assertThatThrownBy(() -> service.create(
                new GameFields("POKEMON", "Pokemon TCG", null, null, null, (short) 0, true), 9L))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ErrorCode.GAME_CODE_ALREADY_USED);

        verify(games, never()).insert(any(), any());
    }

    @Test
    @DisplayName("editing a game leaves its slug alone, because links point at it")
    void updateDoesNotTouchSlug() {
        given(games.findById(POKEMON)).willReturn(Optional.of(pokemon()));
        given(games.codeTaken("POKEMON", POKEMON)).willReturn(false);

        service.update(POKEMON, new GameFields("pokemon", "Pokemon TCG JP", null, "new-slug", null,
                (short) 2, false));

        ArgumentCaptor<GameFields> saved = ArgumentCaptor.forClass(GameFields.class);
        verify(games).update(eq(POKEMON), saved.capture());
        assertThat(saved.getValue().slug()).isNull();
        assertThat(saved.getValue().active()).isFalse();
    }

    @Test
    @DisplayName("an unknown game is 404 rather than an empty list of attributes")
    void attributesOfUnknownGame() {
        given(games.findById(POKEMON)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.attributesOf(POKEMON))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.GAME_NOT_FOUND);
    }

    @Test
    @DisplayName("an attribute key is stored folded and must be free within the game")
    void attributeKeyMustBeFree() {
        given(games.findById(POKEMON)).willReturn(Optional.of(pokemon()));
        given(attributes.keyTaken(POKEMON, "hp", null)).willReturn(true);

        AttributeFields upperCaseKey = new AttributeFields("HP", "HP", AttributeDataType.NUMBER,
                List.of(), true, false, (short) 1);

        assertThatThrownBy(() -> service.addAttribute(POKEMON, upperCaseKey))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).errorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_KEY_ALREADY_USED);
    }

    @Test
    @DisplayName("an ENUM attribute without options is refused before the database says so")
    void enumNeedsOptions() {
        given(games.findById(POKEMON)).willReturn(Optional.of(pokemon()));

        AttributeFields noOptions = new AttributeFields("card_type", "Type",
                AttributeDataType.ENUM, List.of(), true, false, (short) 1);

        assertThatThrownBy(() -> service.addAttribute(POKEMON, noOptions))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ATTRIBUTE_DEFINITION);

        verify(attributes, never()).insert(anyShort(), any());
    }

    @Test
    @DisplayName("only an ENUM may carry options")
    void nonEnumMustNotCarryOptions() {
        given(games.findById(POKEMON)).willReturn(Optional.of(pokemon()));

        AttributeFields numberWithOptions = new AttributeFields("hp", "HP",
                AttributeDataType.NUMBER, List.of("100", "200"), true, false, (short) 1);

        assertThatThrownBy(() -> service.addAttribute(POKEMON, numberWithOptions))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ATTRIBUTE_DEFINITION);
    }

    @Test
    @DisplayName("an attribute belonging to another game is not found through this one")
    void attributeOfAnotherGameIsNotFound() {
        GameAttribute magicAttribute = new GameAttribute(55, (short) 2, "mana_cost", "Mana cost",
                AttributeDataType.NUMBER, List.of(), true, false, (short) 1);

        given(attributes.findById(55)).willReturn(Optional.of(magicAttribute));

        assertThatThrownBy(() -> service.updateAttribute(POKEMON, 55, hp()))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((NotFoundException) e).errorCode())
                .isEqualTo(ErrorCode.ATTRIBUTE_NOT_FOUND);

        verify(attributes, never()).update(anyInt(), any());
    }
}
