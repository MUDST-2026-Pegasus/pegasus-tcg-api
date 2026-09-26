package com.pegasus.pegasustcgapi.model;

import java.time.OffsetDateTime;

/**
 * One slide of the home page carousel.
 *
 * @param title    may hold line breaks; the slide keeps them
 * @param imageKey an object key, never a public URL
 * @param theme    CAMPAIGN, RELEASE or COLLECTOR — how the web client styles it
 * @param startsAt null shows the slide straight away
 * @param endsAt   null keeps it up until someone switches it off
 */
public record HomeBanner(
        int id,
        String eyebrow,
        String title,
        String description,
        String imageKey,
        String imageAlt,
        String theme,
        String primaryLabel,
        String primaryHref,
        String secondaryLabel,
        String secondaryHref,
        short displayOrder,
        boolean active,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt) {
}
