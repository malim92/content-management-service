package com.kairostek.cms.service;

import com.kairostek.cms.domain.Content;
import java.lang.reflect.Field;

/**
 * Test helper: injects an id into a JPA entity that has no public setter.
 */
final class TestEntityUtil {

    private TestEntityUtil() {
    }

    static void setId(Content content, Long id) {
        try {
            Field field = Content.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(content, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
