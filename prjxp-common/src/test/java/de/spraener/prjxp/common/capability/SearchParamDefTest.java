package de.spraener.prjxp.common.capability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchParamDefTest {

    @Test
    void optional_setsRequiredToFalse() {
        SearchParamDef def = SearchParamDef.optional("q", "query param");

        assertThat(def.name()).isEqualTo("q");
        assertThat(def.description()).isEqualTo("query param");
        assertThat(def.required()).isFalse();
    }

    @Test
    void required_setsRequiredToTrue() {
        SearchParamDef def = SearchParamDef.required("id", "identifier param");

        assertThat(def.name()).isEqualTo("id");
        assertThat(def.description()).isEqualTo("identifier param");
        assertThat(def.required()).isTrue();
    }
}
