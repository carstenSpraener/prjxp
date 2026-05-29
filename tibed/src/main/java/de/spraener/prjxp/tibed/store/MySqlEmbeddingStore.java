package de.spraener.prjxp.tibed.store;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MySqlEmbeddingStore implements EmbeddingStore<TextSegment> {

    private final Connection connection;

    public MySqlEmbeddingStore(Connection connection) {
        this.connection = connection;
    }

    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException("Nutzen Sie add(Embedding, TextSegment).");
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException("Nutzen Sie add(Embedding, TextSegment).");
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        String sql = "INSERT INTO prjxp_embeddings (chunk_hash, content, metadata, embedding) VALUES (?, ?, ?, ?)";

        // ID/Hash aus Metadaten auslesen (analog zu Ihrer Ingestion-Logik)
        String id = textSegment.metadata().getString("id");
        if (id == null) {
            id = Integer.toHexString(textSegment.text().hashCode());
        }

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, textSegment.text());

            if (textSegment.metadata() != null) {
                // Konvertiert die Metadata-Map zu einem simplen String/JSON-Format
                stmt.setString(3, textSegment.metadata().toString());
            } else {
                stmt.setNull(3, java.sql.Types.VARCHAR);
            }

            // Umwandlung des float-Arrays in Binärdaten (Little-Endian für PHP-Kompatibilität)
            float[] vector = embedding.vector();
            ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            for (float f : vector) {
                buffer.putFloat(f);
            }

            stmt.setBytes(4, buffer.array());
            stmt.executeUpdate();

            return id;
        } catch (Exception e) {
            throw new RuntimeException("Fehler beim Speichern des Embeddings in MySQL", e);
        }
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return List.of();
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(add(embeddings.get(i), textSegments.get(i)));
        }
        return ids;
    }

    @Override
    public void removeAll(Filter filter) {
        // Wird von EmbeddingService aufgerufen, wenn tibedResetStore true ist
        String sql = "TRUNCATE TABLE prjxp_embeddings";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Fehler beim Zurücksetzen der MySQL-Tabelle", e);
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        // Wichtig für die Methode 'needsEmbedding' im EmbeddingService
        // Prüft, ob der Chunk-Hash (id) bereits in der Datenbank existiert
        Filter filter = request.filter();
        if (filter instanceof IsEqualTo isEqualTo && "id".equals(isEqualTo.key())) {
            String targetId = isEqualTo.comparisonValue().toString();
            String sql = "SELECT COUNT(*) FROM prjxp_embeddings WHERE chunk_hash = ?";

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, targetId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        // Dummy-Match zurückgeben, damit hasEntriesWithFilter true meldet
                        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.0, targetId, null, null);
                        return new EmbeddingSearchResult<>(List.of(match));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Fehler bei Duplikatsprüfung im MySQL-Store", e);
            }
        }
        return new EmbeddingSearchResult<>(Collections.emptyList());
    }
}