package de.spraener.prjxp.docpipe.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DPContentCreationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DPContentCreation fullyPopulated() {
        DPContentCreation c = new DPContentCreation();
        c.setForEach("**/*.java");
        c.setOutputFile(".md");
        c.setOutputDir("docs");
        c.setStereotype("doc-writer");
        c.setPrompt("src-doc.prompt.txt");
        c.setPs("postscript.ps1");
        c.setFilterList("filter-a,filter-b");
        c.setStorePrompt(".prompt.txt");
        c.getArgs().put("key", "value");
        return c;
    }

    @Test
    void defaultArgs_isEmptyHashMap() {
        assertThat(new DPContentCreation().getArgs()).isNotNull().isEmpty();
    }

    @Test
    void gettersAndSetters_roundTrip() {
        DPContentCreation c = fullyPopulated();

        assertThat(c.getForEach()).isEqualTo("**/*.java");
        assertThat(c.getOutputFile()).isEqualTo(".md");
        assertThat(c.getOutputDir()).isEqualTo("docs");
        assertThat(c.getStereotype()).isEqualTo("doc-writer");
        assertThat(c.getPrompt()).isEqualTo("src-doc.prompt.txt");
        assertThat(c.getPs()).isEqualTo("postscript.ps1");
        assertThat(c.getFilterList()).isEqualTo("filter-a,filter-b");
        assertThat(c.getStorePrompt()).isEqualTo(".prompt.txt");
        assertThat(c.getArgs()).containsEntry("key", "value");
    }

    @Test
    void clone_copiesAllFieldsIncludingArgs() {
        DPContentCreation original = fullyPopulated();

        DPContentCreation copy = original.clone(objectMapper);

        assertThat(copy).isNotSameAs(original);
        assertThat(copy.getForEach()).isEqualTo("**/*.java");
        assertThat(copy.getOutputFile()).isEqualTo(".md");
        assertThat(copy.getOutputDir()).isEqualTo("docs");
        assertThat(copy.getStereotype()).isEqualTo("doc-writer");
        assertThat(copy.getPrompt()).isEqualTo("src-doc.prompt.txt");
        assertThat(copy.getPs()).isEqualTo("postscript.ps1");
        assertThat(copy.getFilterList()).isEqualTo("filter-a,filter-b");
        assertThat(copy.getStorePrompt()).isEqualTo(".prompt.txt");
        assertThat(copy.getArgs()).containsEntry("key", "value");

        // deep copy: mutating the original must not affect the clone
        original.getArgs().put("key", "changed");
        assertThat(copy.getArgs()).containsEntry("key", "value");
    }

    @Test
    void equalsAndHashCode_basedOnAllFields() {
        DPContentCreation a = fullyPopulated();
        DPContentCreation b = fullyPopulated();

        assertThat(a).isEqualTo(b)
                .hasSameHashCodeAs(b);

        b.setOutputFile(".rst");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toString_containsFieldNames() {
        assertThat(new DPContentCreation().toString()).contains("outputFile");
    }
}
