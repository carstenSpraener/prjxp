package de.spraener.prjxp.common.retrieval.ranking;

import java.util.List;

/**
 * A named, weighted list of hits, best first.
 * <p>
 * The weight scales the rank-fusion contribution; {@code 0.0} disables the signal without the
 * caller having to remove it. A negative weight is treated like {@code 0.0}.
 */
public record WeightedSignal(String name, double weight, List<RankedItem> items) {

    public WeightedSignal {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean isActive() {
        return weight > 0.0 && !items.isEmpty();
    }
}
