package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.EdgeType;

import java.util.List;
import java.util.Map;

/**
 * One module group of the {@code contentLevel=AGGREGATED} graph view (efficiency measure 5):
 * the edges of one direction collapsed to the module of the neighbour's file.
 *
 * <p>{@code edgeCount} is the number of edges in this group, {@code classCount} the number of
 * distinct classes behind them (method ids collapse to their class), {@code typeCounts} the
 * per-{@link EdgeType} breakdown (descending by count), and {@code topClasses} the most
 * referenced classes (descending by count, capped).
 */
public record GraphAggregate(String module, int edgeCount, int classCount,
                             Map<EdgeType, Integer> typeCounts, List<ClassCount> topClasses) {

    /** One class with its edge count within a module group. */
    public record ClassCount(String className, int count) {
    }
}
