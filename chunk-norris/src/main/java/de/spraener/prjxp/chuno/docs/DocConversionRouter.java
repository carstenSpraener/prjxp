package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.docs.config.ConversionRoutesConfig;
import de.spraener.prjxp.chuno.docs.model.ConversionAccuracy;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import de.spraener.prjxp.common.util.BeanNameFinder;
import lombok.Data;
import lombok.extern.java.Log;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Service
@Data
@Log
public class DocConversionRouter {

    private final List<DocConversionAgent> agents;
    private final BeanNameFinder beanNameFinder;
    private final ConversionRoutesConfig conversionRoutesConfig; // Neu hinzugefügt

    @Value("${prjxp.conversion.inaccurateSurcharge:1000000.0}")
    private double inaccurateSurcharge;

    /**
     * Constructs a new {@code DocConversionRouter} with the given list of conversion agents.
     *
     * @param agents The list of available {@link DocConversionAgent}s.
     * @param beanNameFinder A utility to find bean names.
     * @param conversionRoutesConfig Configuration for predefined conversion routes.
     */
    public DocConversionRouter(List<DocConversionAgent> agents, BeanNameFinder beanNameFinder, ConversionRoutesConfig conversionRoutesConfig) {
        this.agents = agents;
        this.beanNameFinder = beanNameFinder;
        this.conversionRoutesConfig = conversionRoutesConfig;
        log.info("The conversion system knows the following DocConversionAgents:");
        for (DocConversionAgent agent : agents) {
            log.info("   - "+beanNameFinder.findBeanName(agent)+" converts (from %s to %s)".formatted(agent.getSourceFormat(), agent.getTargetFormat()));
        }
    }

    private Graph<DocArtifaktType, DocConversionAgent<?, ?>> buildGraph() {
        Graph<DocArtifaktType, DocConversionAgent<?, ?>> conversionGraph = new SimpleDirectedWeightedGraph(DocConversionAgent.class);
        for (DocConversionAgent agent : agents) {
            conversionGraph.addVertex(agent.getSourceFormat());
            conversionGraph.addVertex(agent.getTargetFormat());
            conversionGraph.addEdge(agent.getSourceFormat(), agent.getTargetFormat(), agent);
        }
        return conversionGraph;
    }

    public List<DocConversionAgent<?, ?>> findBestPath(File f, DocArtifaktType start, DocArtifaktType end, ConversionAccuracy accuracy) {
        List<DocConversionAgent<?, ?>> predefinedAgents = conversionRoutesConfig.findPredefinedRouteAgents(start, end);
        if (!predefinedAgents.isEmpty()) {
            log.fine("Using predefined route for %s to %s.".formatted(start.name(), end.name()));
            return predefinedAgents;
        }
        Graph<DocArtifaktType, DocConversionAgent<?, ?>> conversionGraph = buildGraph();
        estimateConversionCosts(conversionGraph, DocArtifakt.createRoot(f), end, accuracy, new HashSet<>());
        DijkstraShortestPath<DocArtifaktType, DocConversionAgent<?, ?>> dijkstra = new DijkstraShortestPath<>(conversionGraph);
        GraphPath<DocArtifaktType, DocConversionAgent<?, ?>> path = dijkstra.getPath(start, end);
        return (path != null) ? path.getEdgeList() : Collections.emptyList();
    }

   private void estimateConversionCosts(Graph<DocArtifaktType, DocConversionAgent<?, ?>> conversionGraph, DocArtifakt f, DocArtifaktType end, ConversionAccuracy accuracy, Set<DocConversionAgent<?, ?>> visited) {
        if (f.getFormat() == end) {
            return;
        }
        for (var agent : agents) {
            if (agent.getSourceFormat() == f.getFormat() && !visited.contains(agent)) {
                var costs = agent.estimateCosts(f);
                var quantity = agent.estimateQuantity(f);
                var agentAccuracy = agent.accuracy();
                var totalCosts = costs * quantity;
                if (agentAccuracy.getRank() < accuracy.getRank()) {
                    totalCosts += inaccurateSurcharge;
                }
                conversionGraph.setEdgeWeight(agent, totalCosts);
                DocArtifakt<?, ?> next = new DocArtifakt<>(f)
                        .setFormat(agent.getTargetFormat())
                        .setChildQuantityEstimation(quantity);
                Set<DocConversionAgent<?, ?>> nextVisited = new HashSet<>(visited);
                nextVisited.add(agent);

                estimateConversionCosts(conversionGraph, next, end, accuracy, nextVisited);
            }
        }
    }

    public String doConversion(File f, DocArtifaktType start, DocArtifaktType end, ConversionAccuracy accuracy) {
        List<DocConversionAgent<?, ?>> agents = findBestPath(f, start, end, accuracy);
        log.fine(() -> listAgentPath(agents));
        DocArtifakt root = DocArtifakt.createRoot(f);
        root.setFormat(start);
        doConversion(root, agents);
        return DocContentCollector.collectTextContent(root, end);
    }

    private String listAgentPath(List<DocConversionAgent<?, ?>> agents) {
        StringBuilder sb = new StringBuilder();
        for (var a : agents) {
            if( !sb.isEmpty() ) {
                sb.append(" -> ");
            }
            String beanName = beanNameFinder.findBeanName(a);
            if(beanName == null) { // Fallback if beanNameFinder doesn't find it (e.g., for non-Spring-managed agents)
                beanName = a.getClass().getSimpleName();
            }
            sb.append(beanName);
            sb.append("[%s>>%s]".formatted(a.getSourceFormat(), a.getTargetFormat()));
        }
        return "Selected conversion path is: "+sb.toString();
    }

    public void doConversion(File f, DocArtifaktType start, DocArtifaktType end, ConversionAccuracy accuracy, Consumer<DocArtifakt> artifaktCollector) {
        List<DocConversionAgent<?, ?>> agents = findBestPath(f, start, end, accuracy);
        DocArtifakt root = DocArtifakt.createRoot(f);
        doConversion(root, agents);
        DocContentCollector.collectContent(root, end, artifaktCollector);
    }

    private void doConversion(DocArtifakt artifakt, List<DocConversionAgent<?, ?>> agents) {
        if (agents.isEmpty()) {
            return;
        }
        DocConversionAgent<?, ?> agent = agents.get(0);
        agent.convert(artifakt);
        List<DocConversionAgent<?, ?>> remainingAgents = agents.subList(1, agents.size());
        if (artifakt.getChilds() != null) {
            for (var child : artifakt.getChilds()) {
                doConversion((DocArtifakt) child, remainingAgents);
            }
        }
    }
}
