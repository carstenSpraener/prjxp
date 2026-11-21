package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The P2 descriptions are sent on every turn. This guard keeps them from growing back into a
 * catalogue: the whole set must stay well below the P1 schema cost (about 4_150 characters).
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

        assertThat(total).isLessThanOrEqualTo(3_200);
    }

    @Test
    void theProjectIsDiscoveredOnceInsteadOfRepeatedInEveryDescription() throws IllegalAccessException {
        assertThat(PrjxpP2ToolDocs.LIST_PROJECTS).contains("project");
        for (Field field : PrjxpP2ToolDocs.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            if (field.getName().equals("LIST_PROJECTS")) {
                continue;
            }
            assertThat((String) field.get(null)).as(field.getName()).doesNotContain("project");
        }
    }
}
