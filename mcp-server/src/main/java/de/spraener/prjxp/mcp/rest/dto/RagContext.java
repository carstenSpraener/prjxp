package de.spraener.prjxp.mcp.rest.dto;

/** RAG context snippet retrieved from a project's chunk store. */
public class RagContext {
    private String projectName;
    private String chunkId;
    private String snippet;
    private Double score;

    public RagContext() {}

    public RagContext(String projectName, String chunkId, String snippet, Double score) {
        this.projectName = projectName;
        this.chunkId = chunkId;
        this.snippet = snippet;
        this.score = score;
    }

    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }

    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
}
