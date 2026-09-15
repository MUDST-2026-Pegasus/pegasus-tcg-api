package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.Slugs;
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
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The games the platform trades in, and the fields each one's cards have [RQ-3].
 *
 * <p>Supporting a new game is meant to be an afternoon of data entry rather than
 * a release: a row in {@code game}, a handful in {@code game_attribute}, and the
 * catalogue can hold its cards and the browse page can filter them.
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    /** {@code game.slug} is varchar(100). */
    private static final int SLUG_WIDTH = 100;

    private final GameRepository games;
    private final GameAttributeRepository attributes;

    public GameService(GameRepository games, GameAttributeRepository attributes) {
        this.games = games;
        this.attributes = attributes;
    }

    // ---------- games ----------

    public List<Game> list(boolean includeInactive) {
        return games.findAll(includeInactive);
    }

    public Game require(short gameId) {
        return games.findById(gameId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.GAME_NOT_FOUND));
    }

    /** For public reads: a retired game is not found, as it is left out of the public list. */
    public Game requireActive(short gameId) {
        return games.findById(gameId)
                .filter(Game::active)
                .orElseThrow(() -> new NotFoundException(ErrorCode.GAME_NOT_FOUND));
    }

    /**
     * @param slug optional; left out, it is built from the name, and a clash picks
     *             up a numeric suffix rather than failing
     */
    @Transactional
    public Game create(GameFields requested, Long createdBy) {
        String code = normaliseCode(requested.code());
        if (games.codeTaken(code, null)) {
            throw new ConflictException(ErrorCode.GAME_CODE_ALREADY_USED);
        }

        String slug = Slugs.unique(requested.slug(), SLUG_WIDTH, games::slugTaken, requested.name());
        GameFields fields = new GameFields(code, requested.name().trim(), blankToNull(requested.nameLocal()),
                slug, blankToNull(requested.logoUrl()), requested.displayOrder(), requested.active());

        short id = games.insert(fields, createdBy);
        log.info("Created game {} ({}) as id {}", fields.name(), code, id);
        return require(id);
    }

    @Transactional
    public Game update(short gameId, GameFields requested) {
        require(gameId);

        String code = normaliseCode(requested.code());
        if (games.codeTaken(code, gameId)) {
            throw new ConflictException(ErrorCode.GAME_CODE_ALREADY_USED);
        }

        games.update(gameId, new GameFields(code, requested.name().trim(),
                blankToNull(requested.nameLocal()), null, blankToNull(requested.logoUrl()),
                requested.displayOrder(), requested.active()));

        return require(gameId);
    }

    // ---------- attributes ----------

    public List<GameAttribute> attributesOf(short gameId) {
        require(gameId);
        return attributes.findByGameId(gameId);
    }

    @Transactional
    public GameAttribute addAttribute(short gameId, AttributeFields requested) {
        require(gameId);

        AttributeFields fields = validated(requested);
        if (attributes.keyTaken(gameId, fields.attrKey(), null)) {
            throw new ConflictException(ErrorCode.ATTRIBUTE_KEY_ALREADY_USED);
        }

        int id = attributes.insert(gameId, fields);
        return requireAttribute(gameId, id);
    }

    /**
     * Editing an attribute does not touch the values already stored under its key.
     * Renaming the key or narrowing an ENUM therefore strands them, which is why
     * the catalogue validates a product's attributes on every write rather than
     * trusting what was valid when it was first saved.
     */
    @Transactional
    public GameAttribute updateAttribute(short gameId, int attributeId, AttributeFields requested) {
        requireAttribute(gameId, attributeId);

        AttributeFields fields = validated(requested);
        if (attributes.keyTaken(gameId, fields.attrKey(), attributeId)) {
            throw new ConflictException(ErrorCode.ATTRIBUTE_KEY_ALREADY_USED);
        }

        attributes.update(attributeId, fields);
        return requireAttribute(gameId, attributeId);
    }

    @Transactional
    public void deleteAttribute(short gameId, int attributeId) {
        requireAttribute(gameId, attributeId);
        attributes.delete(attributeId);
        log.info("Deleted attribute {} of game {}", attributeId, gameId);
    }

    /** Reads the attribute through its game, so an id from another game is simply not found. */
    private GameAttribute requireAttribute(short gameId, int attributeId) {
        return attributes.findById(attributeId)
                .filter(attribute -> attribute.gameId() == gameId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ATTRIBUTE_NOT_FOUND));
    }

    /**
     * The database CHECK already refuses an ENUM with no list and a non-ENUM with
     * one. Repeating it here is what turns a constraint violation into an answer
     * that says which field was wrong.
     */
    private static AttributeFields validated(AttributeFields requested) {
        AttributeDataType type = requested.dataType();
        List<String> options = requested.options() == null ? List.of() : requested.options();

        if (type.requiresOptions() && options.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ATTRIBUTE_DEFINITION,
                    "An ENUM attribute needs at least one option");
        }
        if (!type.requiresOptions() && !options.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ATTRIBUTE_DEFINITION,
                    "Only an ENUM attribute may carry options; " + type + " must not");
        }

        return new AttributeFields(
                requested.attrKey().trim().toLowerCase(Locale.ROOT),
                requested.label().trim(),
                type,
                options,
                requested.filterable(),
                requested.required(),
                requested.displayOrder());
    }

    /** Codes travel in URLs and other systems match on them, so they are stored folded. */
    private static String normaliseCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
