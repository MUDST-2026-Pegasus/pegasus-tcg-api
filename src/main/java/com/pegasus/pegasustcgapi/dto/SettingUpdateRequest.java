package com.pegasus.pegasustcgapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param value the new value as JSON, so a setting can be a number, a string or
 *        an object without a schema change — {@code 7}, {@code "THB"}, {@code {"a":1}}
 */
public record SettingUpdateRequest(

        @NotBlank @Size(max = 4000)
        String value) {
}
