package de.spraener.prjxp.common.config;

import de.spraener.prjxp.common.chat.BodyPropertyCoercer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Spring relaxed-binding path for {@link PrjXPChatModelReference#getBodyProperties()}.
 * <p>
 * The critical guarantee: no matter whether Spring binds a YAML scalar as its native type
 * ({@code false} → {@link Boolean}) or as a String (env-var / quoted-YAML case), the
 * {@link BodyPropertyCoercer} yields a native JSON boolean so the request body carries
 * {@code "enable_thinking": false} rather than {@code "enable_thinking": "false"}.
 */
class ChatModelBodyPropertiesBindingTest {

    private PrjXPChatModelReference bindChatModel(String thinkingValue) {
        Map<String, Object> source = Map.of(
                "prjxp.chatmodels[0].stereotyp", "documentation",
                "prjxp.chatmodels[0].servertyp", "lm-studio",
                "prjxp.chatmodels[0].bodyproperties.enable_thinking", thinkingValue
        );
        Binder binder = new Binder(new MapConfigurationPropertySource(source));
        PrjXPConfig config = binder.bind("prjxp", PrjXPConfig.class).get();
        return config.getChatModels().get(0);
    }

    @Test
    void yamlScalar_bindsAsString() {
        PrjXPChatModelReference ref = bindChatModel("false");

        // Spring relaxed binding materializes Map<String,Object> scalar values as Strings,
        // even when the source is a typed YAML boolean. This is why the coercion step exists.
        Object raw = ref.getBodyProperties().get("enable_thinking");
        assertThat(raw).isInstanceOf(String.class);
        assertThat(raw).isEqualTo("false");
    }

    @Test
    void boundString_coercesToNativeBoolean() {
        PrjXPChatModelReference ref = bindChatModel("false");

        // The supplier applies the coercion, so the request body ends up with a real JSON boolean.
        Map<String, Object> coerced = BodyPropertyCoercer.coerce(ref.getBodyProperties());
        assertThat(coerced.get("enable_thinking")).isEqualTo(false);
        assertThat(coerced.get("enable_thinking")).isInstanceOf(Boolean.class);
    }
}
