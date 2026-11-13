package de.spraener.prjxp.lucene;

import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;

import java.io.IOException;

/**
 * Materialises a query filter as a random-access bit set per leaf.
 * <p>
 * A {@link DocIdSetIterator} only moves forward: {@code advance(target)} is undefined when
 * {@code target} is at or behind the current document (see its contract). That breaks callers
 * which walk a different, denser doc-id space — the vector values — and test filter membership
 * per document. On the real prjxp index Lucene 10 then threw
 * {@code ArrayIndexOutOfBoundsException: Index 256 out of bounds for length 256} inside
 * {@code Lucene104PostingsReader$BlockPostingsEnum.advance}. A bit set gives O(1) membership
 * tests without any iteration-order contract.
 */
final class FilterDocs {

    private FilterDocs() {
    }

    /** All live documents of {@code weight} in the given leaf, addressable by local doc id. */
    static FixedBitSet matchingDocs(Weight weight, LeafReaderContext leafContext) throws IOException {
        LeafReader leaf = leafContext.reader();
        FixedBitSet matches = new FixedBitSet(leaf.maxDoc());
        Scorer scorer = weight.scorer(leafContext);
        if (scorer == null) {
            return matches;
        }
        Bits liveDocs = leaf.getLiveDocs();
        DocIdSetIterator iterator = scorer.iterator();
        for (int docId = iterator.nextDoc();
             docId != DocIdSetIterator.NO_MORE_DOCS;
             docId = iterator.nextDoc()) {
            if (liveDocs == null || liveDocs.get(docId)) {
                matches.set(docId);
            }
        }
        return matches;
    }
}
