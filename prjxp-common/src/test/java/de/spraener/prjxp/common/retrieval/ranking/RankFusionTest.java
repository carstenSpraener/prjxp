package de.spraener.prjxp.common.retrieval.ranking;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RankFusionTest {

    private static List<WeightedSignal> signal(String name, RankedItem... items) {
        return List.of(new WeightedSignal(name, 1.0, List.of(items)));
    }

    private static List<WeightedSignal> weighted(String name, double weight, RankedItem... items) {
        return List.of(new WeightedSignal(name, weight, List.of(items)));
    }

    private static RankedItem item(String id, String file) {
        return new RankedItem(id, file, 0.0);
    }

    private static List<WeightedSignal> withSignals(List<WeightedSignal>... signals) {
        List<WeightedSignal> all = new ArrayList<>();
        for (List<WeightedSignal> s : signals) {
            all.addAll(s);
        }
        return all;
    }

    @Test
    void singleSignalKeepsItsOrder() {
        List<RankedItem> ranked = RankFusion.DEFAULT.rankItems(
                signal("s", item("a", "A.java"), item("b", "B.java"), item("c", "C.java")), 10);

        assertThat(ranked).extracting(RankedItem::id).containsExactly("a", "b", "c");
        assertThat(ranked.get(0).score()).isCloseTo(1.0 / 61, within(1e-9));
        assertThat(ranked.get(1).score()).isCloseTo(1.0 / 62, within(1e-9));
    }

    @Test
    void itemInTwoSignalsBeatsItemInOne() {
        List<WeightedSignal> signals = withSignals(
                signal("s1", item("x1", "X1.java"), item("x2", "X2.java"), item("a", "A.java")),
                signal("s2", item("b", "B.java"), item("y", "Y.java"), item("a", "A.java")));

        List<RankedItem> ranked = RankFusion.DEFAULT.rankItems(signals, 10);

        assertThat(ranked).extracting(RankedItem::id).startsWith("a", "b");
    }

    @Test
    void missingItemIsNotPenalized() {
        List<WeightedSignal> signals = withSignals(
                signal("s1", item("a", "A.java"), item("b", "B.java")),
                signal("s2", item("b", "B.java"), item("c", "C.java")));

        assertThat(RankFusion.DEFAULT.rankItems(signals, 10))
                .extracting(RankedItem::id)
                .containsExactly("b", "a", "c");
    }

    @Test
    void duplicateIdsCountWithTheirBestRank() {
        List<RankedItem> ranked = RankFusion.DEFAULT.rankItems(
                signal("s", item("a", "A.java"), item("b", "B.java"), item("a", "A.java"),
                        item("c", "C.java")), 10);

        assertThat(ranked).extracting(RankedItem::id).containsExactly("a", "b", "c");
        assertThat(ranked.get(0).score()).isCloseTo(1.0 / 61, within(1e-9));
        assertThat(ranked.get(2).score()).isCloseTo(1.0 / 63, within(1e-9));
    }

    @Test
    void weightScalesContribution() {
        List<RankedItem> half = RankFusion.DEFAULT.rankItems(weighted("s", 0.5, item("a", "A.java")), 10);
        List<RankedItem> off = RankFusion.DEFAULT.rankItems(weighted("s", 0.0, item("a", "A.java")), 10);

        assertThat(half.get(0).score()).isCloseTo(0.5 / 61, within(1e-9));
        assertThat(off).isEmpty();
    }

    @Test
    void limitAppliesAfterFusion() {
        List<WeightedSignal> signals = withSignals(
                signal("s1", item("a", "A.java"), item("b", "B.java"), item("c", "C.java")),
                signal("s2", item("c", "C.java")));

        assertThat(RankFusion.DEFAULT.rankItems(signals, 2))
                .extracting(RankedItem::id)
                .containsExactly("c", "a");
    }

    @Test
    void emptySignalsYieldEmptyResult() {
        assertThat(RankFusion.DEFAULT.rankItems(List.of(), 10)).isEmpty();
        assertThat(RankFusion.DEFAULT.rankItems(signal("s"), 10)).isEmpty();
    }

    @Test
    void fileAggregationUsesMaxNotSum() {
        List<RankedItem> manyWeak = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            manyWeak.add(item("a" + i, "A.java"));
        }
        // A sum would make A.java (5 * 0.5/61 = 0.041) beat B.java (1/61); the max (0.5/61) must lose.
        List<WeightedSignal> signals = List.of(
                new WeightedSignal("weak", 0.5, manyWeak),
                new WeightedSignal("strong", 1.0, List.of(item("b", "B.java"))));

        assertThat(RankFusion.DEFAULT.rankFiles(signals, 10))
                .extracting(RankedFile::file)
                .containsExactly("B.java", "A.java");
    }

    @Test
    void fileAggregationCountsChunks() {
        List<WeightedSignal> signals = List.of(new WeightedSignal("s", 1.0, List.of(
                item("c1", "A.java"), item("c2", "A.java"), item("c3", "A.java"),
                item("c4", "B.java"))));

        assertThat(RankFusion.DEFAULT.rankFiles(signals, 10))
                .extracting(RankedFile::file, RankedFile::chunks)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A.java", 3),
                        org.assertj.core.groups.Tuple.tuple("B.java", 1));
    }

    @Test
    void orderIsDeterministicOnTies() {
        List<WeightedSignal> signals = withSignals(
                signal("s1", item("x", "b.java")),
                signal("s2", item("y", "a.java")));

        List<RankedFile> first = RankFusion.DEFAULT.rankFiles(signals, 10);
        List<RankedFile> second = RankFusion.DEFAULT.rankFiles(signals, 10);

        assertThat(first).extracting(RankedFile::file).containsExactly("a.java", "b.java");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void kIsConfigurable() {
        List<WeightedSignal> signals = List.of(
                new WeightedSignal("top", 1.0, List.of(item("x", "A.java"))),
                new WeightedSignal("s2", 1.0, List.of(item("f1", "F1.java"), item("f2", "F2.java"),
                        item("y", "Z.java"))),
                new WeightedSignal("s3", 1.0, List.of(item("g1", "G1.java"), item("g2", "G2.java"),
                        item("y", "Z.java"))));

        // k=60: two mid-rank confirmations (2/63) beat one top hit (1/61).
        assertThat(RankFusion.DEFAULT.rankItems(signals, 1))
                .extracting(RankedItem::id)
                .containsExactly("y");
        // k=1 flattens the ranks: everything ties at 0.5 and the file tie-break decides.
        assertThat(RankFusion.DEFAULT.withK(1).rankItems(signals, 1))
                .extracting(RankedItem::id)
                .containsExactly("x");
    }

    @Test
    void fileSignalsConfirmEachOtherAcrossDifferentIds() {
        // A name hit carries the file as id, a content hit carries a chunk id. Same file, two keys
        // — the file ranking must still see them as one confirmation, not as two unrelated rows.
        List<WeightedSignal> signals = List.of(
                new WeightedSignal("lexical", 1.0, List.of(
                        item("c1", "Other.java"), item("c2", "Runner.java"))),
                new WeightedSignal("name", 1.0, List.of(item("Runner.java", "Runner.java"))));

        List<RankedFile> ranked = RankFusion.DEFAULT.rankFiles(signals, 10);

        assertThat(ranked).extracting(RankedFile::file).containsExactly("Runner.java", "Other.java");
        assertThat(ranked.get(0).chunks()).isEqualTo(2);
    }

    @Test
    void limitBelowOneYieldsEmptyResult() {
        assertThat(RankFusion.DEFAULT.rankItems(signal("s", item("a", "A.java")), 0)).isEmpty();
        assertThat(RankFusion.DEFAULT.rankFiles(signal("s", item("a", "A.java")), -1)).isEmpty();
    }

    @Test
    void itemsWithoutFileAreNotAggregatedToAFile() {
        List<WeightedSignal> signals = signal("s", item("a", null), item("b", " "), item("c", "C.java"));

        assertThat(RankFusion.DEFAULT.rankFiles(signals, 10))
                .extracting(RankedFile::file)
                .containsExactly("C.java");
    }
}
