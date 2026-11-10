package dev.katherine.consilium;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * Interface strings and agent instructions. The IDE picks the language: English by default,
 * {@code ConsiliumBundle_ru.properties} when the IDE runs with a Russian locale.
 */
public final class ConsiliumBundle extends DynamicBundle {
    @NonNls
    private static final String BUNDLE = "messages.ConsiliumBundle";
    private static final ConsiliumBundle INSTANCE = new ConsiliumBundle();

    private ConsiliumBundle() {
        super(ConsiliumBundle.class, BUNDLE);
    }

    public static @Nls @NotNull String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }
}
