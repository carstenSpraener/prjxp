package de.spraener.prjxp.tibed.config;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.store.MySqlEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@Log
public class EmbeddingStoreSupplier implements org.springframework.beans.factory.DisposableBean {
    private final PrjXPConfig cfg;
    private final org.springframework.beans.factory.ObjectProvider<LuceneEmbeddingStore> sharedLucene;
    private EmbeddingStore<TextSegment> createdStore;

    public EmbeddingStore<TextSegment> getStore(String name) {
        // Lucene embedded store (braucht keine externe Store-Referenz)
        if (cfg.getEmbeddingStoreType() == PrjXPConfig.EmbeddingStoreType.LUCENE) {
            LuceneEmbeddingStore shared = sharedLucene.getIfAvailable();
            if (shared != null) { return shared; }   // hub / auto-config bean — never a second writer
            PrjXPConfig.LuceneEmbeddingStoreConfig lc = cfg.getEmbeddingStoreLucene();
            log.info("Initialisiere Lucene Embedding Store für das Projekt: " + name
                    + ", index path: " + lc.getIndexPath());
            createdStore = new LuceneEmbeddingStore(Path.of(lc.getIndexPath()), lc.getVectorDimension());
            return createdStore;
        }

        ProjectDefinition pd = cfg.getProjectDefinition(name).orElseThrow(() -> new IllegalStateException(
                "No project definition for '" + name + "'. Available projects: "
                        + cfg.getProjects().stream().map(ProjectDefinition::getName).toList()));

        PrjXPEmbeddingStoreReference ref = cfg.getEmbeddingStores()
                .stream()
                .filter(r -> r.getProjectName().equals(pd.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No store found for project " + pd.getName()));

        // Überprüfen, ob als Provider-URL eine JDBC-MySQL-Verbindung hinterlegt ist
        if (ref.getProviderUrl() != null && ref.getProviderUrl().startsWith("jdbc:mysql:")) {
            log.info("Initialisiere lokalen MySQL Vektorspeicher für das Projekt: " + pd.getName());
            try {
                // Nutzen Sie hier Ihre gewünschten Credentials aus der Konfiguration/Dotenv
                Connection conn = DriverManager.getConnection(ref.getProviderUrl(), "root", "");
                return new MySqlEmbeddingStore(conn);
            } catch (Exception e) {
                throw new RuntimeException("Verbindungsaufbau zur lokalen MySQL-Datenbank fehlgeschlagen", e);
            }
        }

        // Fallback auf das bestehende Chroma-Setup
        log.info(String.format("Using ChromaStore at '%s' as tenant '%s', database '%s' and collection '%s'",
                ref.getProviderUrl(), ref.getTenant(), ref.getDbName(), ref.getCollectionName()));

        return ChromaEmbeddingStore.builder()
                .baseUrl(ref.getProviderUrl())
                .apiVersion(ChromaApiVersion.V2)
                .tenantName(ref.getTenant())
                .databaseName(ref.getDbName())
                .timeout(Duration.ofSeconds(ref.getTimeoutSecs()))
                .collectionName(ref.getCollectionName())
                .build();
    }

    @Override
    public void destroy() {
        if (createdStore instanceof LuceneEmbeddingStore) {
            log.info("Closing Lucene embedding store...");
            ((LuceneEmbeddingStore) createdStore).close();
        }
    }
}
