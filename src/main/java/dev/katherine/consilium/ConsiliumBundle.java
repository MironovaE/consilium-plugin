package dev.katherine.consilium;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * Строки интерфейса и инструкций агентам. Язык выбирает IDE: английский по умолчанию,
 * русский — {@code ConsiliumBundle_ru.properties}, когда IDE запущена с русской локалью.
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
