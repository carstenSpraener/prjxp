package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DocConversionRouter {

    private final List<DocConversionAgent> agents;

    public DocConversionRouter(List<DocConversionAgent> agents) {
        this.agents = agents;
    }

    private Graph<DocArtifaktType, DocConversionAgent<?,?>> buildGraph() {
        Graph<DocArtifaktType, DocConversionAgent<?,?>> conversionGraph = new SimpleDirectedWeightedGraph(DocConversionAgent.class);
        for (DocConversionAgent agent : agents) {
            conversionGraph.addVertex(agent.getSourceFormat());
            conversionGraph.addVertex(agent.getTargetFormat());
            conversionGraph.addEdge(agent.getSourceFormat(), agent.getTargetFormat(), agent);
        }
        return conversionGraph;
    }

    public List<DocConversionAgent<?,?>> findBestPath(File f, DocArtifaktType start, DocArtifaktType end) {
        Graph<DocArtifaktType, DocConversionAgent<?,?>> conversionGraph = buildGraph();
        estimateConversionCosts(conversionGraph, DocArtifakt.createRoot(f), end, new HashSet<>());
        DijkstraShortestPath<DocArtifaktType, DocConversionAgent<?,?>> dijkstra = new DijkstraShortestPath<>(conversionGraph);
        GraphPath<DocArtifaktType, DocConversionAgent<?,?>> path = dijkstra.getPath(start, end);
        return (path != null) ? path.getEdgeList() : Collections.emptyList();
    }

    private void estimateConversionCosts(Graph<DocArtifaktType, DocConversionAgent<?,?>> conversionGraph, DocArtifakt f, DocArtifaktType end, Set<DocConversionAgent<?,?>> visited) {
        if (f.getFormat() == end) {
            return;
        }
        for( var agent : agents ) {
            if( agent.getSourceFormat() == f.getFormat() && !visited.contains(agent) ) {
                var costs = agent.estimateCosts(f);
                var quantity = agent.estimateQuantity(f);
                conversionGraph.setEdgeWeight(agent, costs);
                DocArtifakt<?,?> next = new DocArtifakt<>(f)
                        .setFormat(agent.getTargetFormat())
                        .setChildQuantityEstimation(quantity)
                        ;
                Set<DocConversionAgent<?, ?>> nextVisited = new HashSet<>(visited);
                nextVisited.add(agent);

                estimateConversionCosts(conversionGraph, next, end, nextVisited);            }
        }
    }

    public <T> T runConversion(File f, DocArtifaktType start, DocArtifaktType end) {
        List<DocConversionAgent<?,?>> agents = findBestPath(f, start, end);
        DocArtifakt root = DocArtifakt.createRoot(f);
        runConversion(root, agents);
        return DocContentCollector.collectContent(root, end);
    }

    private void runConversion(DocArtifakt artifakt, List<DocConversionAgent<?,?>> agents) {
        if (agents.isEmpty()) {
            return;
        }
        DocConversionAgent<?,?> agent = agents.get(0);
        agent.convert(artifakt);
        List<DocConversionAgent<?,?>> remainingAgents = agents.subList(1, agents.size());
        if( artifakt.getChilds() != null ) {
            for (var child : artifakt.getChilds()) {
                runConversion((DocArtifakt) child, remainingAgents);
            }
        }
    }
}