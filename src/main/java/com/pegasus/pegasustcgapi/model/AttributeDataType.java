package com.pegasus.pegasustcgapi.model;

/**
 * What kind of value a {@link GameAttribute} holds. The values themselves live in
 * {@code catalog_product.attributes}, so this is what tells the service how to
 * read one and the front end how to draw a filter for it.
 */
public enum AttributeDataType {

    STRING,
    NUMBER,
    BOOLEAN,
    /** The only type with a choice list; see {@link GameAttribute#options()}. */
    ENUM,
    DATE;

    public boolean requiresOptions() {
        return this == ENUM;
    }
}
