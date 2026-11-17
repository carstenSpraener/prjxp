package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The P2 descriptions are sent on every turn. This guard keeps them from growing back into a
 * catalogue: the whole set must stay well below the P1 schema cost.
 */
class PrjxpP2ToolDocsTest {

    @Test
    void allDescriptionsTogetherStayBelowTheBudget() throws IllegalAccessException {
        int total = 0;
        for (Field field : PrjxpP2ToolDocs.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                total += ((String) field.get(null)).length();
            }
        }

        assertThat(total).isLessThanOrEqualTo(1_800);
    }

    @Test
    void noDescriptionMentionsAProjectParameter() throws IllegalAccessException {
        for (Field field : PrjxpP2ToolDocs.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                assertThat((String) field.get(null)).doesNotContain("project");
            }
        }
    }
}
