package com.codex.desktreadmill;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

public final class TreadmillBundle extends DynamicBundle {
    private static final String BUNDLE = "messages.TreadmillBundle";
    private static final TreadmillBundle INSTANCE = new TreadmillBundle();

    private TreadmillBundle() {
        super(BUNDLE);
    }

    public static @Nls @NotNull String message(
            @PropertyKey(resourceBundle = BUNDLE) @NotNull String key,
            Object @NotNull ... params
    ) {
        return INSTANCE.getMessage(key, params);
    }
}
