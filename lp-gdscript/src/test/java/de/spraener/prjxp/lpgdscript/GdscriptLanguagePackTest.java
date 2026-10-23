package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GdscriptLanguagePackTest {

    private final GdscriptLanguagePack pack = new GdscriptLanguagePack();

    @SuppressWarnings("unchecked") // PxChunk.create(Consumer<PxChunk>...) varargs; a single consumer is always passed
    private static PxChunk gd(String id, String section, String parent, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setParent(parent);
            c.setMimeType(GdscriptCodeChunker.GDSCRIPT_CODE_MIME_TYPE);
            c.setPart(0);
            c.setTotal(1);
            c.setContent(content);
            c.getMetadata().put(GdscriptCodeSection.METADATA_KEY, section);
        });
    }

    @Test
    void languageAndMimeType() {
        assertThat(pack.language()).isEqualTo("gdscript");
        assertThat(pack.mimeType()).isEqualTo("text/x-gdscript-code");
    }

    @Test
    void providerCreatesPack() {
        LanguagePack created = new GdscriptLanguagePackProvider().create();
        assertThat(created).isInstanceOf(GdscriptLanguagePack.class);
        assertThat(created.language()).isEqualTo("gdscript");
    }

    @Test
    void findPxChunkersMatchesGdFilesOnly() {
        assertThat(pack.findPxChunkers(new File("Player.gd"))).hasSize(1);
        assertThat(pack.findPxChunkers(new File("Player.java"))).isEmpty();
        assertThat(pack.findPxChunkers(new File("Player.gd.txt"))).isEmpty();
    }

    @Test
    void retrieverAndFileViewProviderPresent() {
        assertThat(pack.retriever()).isPresent();
        assertThat(pack.fileViewProvider()).isPresent();
        FileViewProvider fvp = pack.fileViewProvider().orElseThrow();
        assertThat(fvp.language()).isEqualTo("gdscript");
        assertThat(fvp.mimeType()).isEqualTo("text/x-gdscript-code");
    }

    @Test
    @SuppressWarnings("unchecked") // varargs call through the GoldenRetriever interface type
    void unboundRetrieverIsSelfContained() {
        // without bind() the retriever has no dao and must not enrich
        GoldenRetriever r = pack.retriever().orElseThrow();
        PxChunk move = gd("Player.move(dir)", "func", "Player", "func move(dir):\n\tpass\n");
        List<SearchHit> hits = r.retrieveSearchHits("proj", List.of(new ScoredChunk(move, 0.9)));
        assertThat(hits.get(0).snippet()).doesNotContain("# enclosing class:");
    }

    @Test
    @SuppressWarnings("unchecked") // varargs call through the GoldenRetriever interface type
    void bindRecreatesRetrieverWithDao() {
        GoldenRetriever before = pack.retriever().orElseThrow();

        InMemoryChunkDao dao = new InMemoryChunkDao();
        dao.put("Player", gd("Player", "header", null, "class_name Player\nextends Node2D\n"));
        dao.put("Player.move(dir).doc", gd("Player.move(dir).doc", "funcDoc", "Player.move(dir)", "## Moves.\n"));
        pack.bind(new TestChunkDaoProvider(dao));

        GoldenRetriever after = pack.retriever().orElseThrow();
        assertThat(after).isNotSameAs(before);

        // the bound retriever enriches from the dao
        PxChunk move = gd("Player.move(dir)", "func", "Player", "func move(dir):\n\tpass\n");
        List<SearchHit> hits = after.retrieveSearchHits("proj", List.of(new ScoredChunk(move, 0.9)));
        assertThat(hits.get(0).snippet()).contains("# enclosing class: Player");
        assertThat(hits.get(0).snippet()).contains("## Moves.");
    }
}
