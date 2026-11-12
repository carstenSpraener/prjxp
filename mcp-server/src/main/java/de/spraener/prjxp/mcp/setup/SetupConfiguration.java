package de.spraener.prjxp.mcp.setup;
public record SetupConfiguration(SetupStatus status, EmbeddingConfigRequest embedding, ChatModelRequest chat,
                                  GithubConfigRequest github, OptimizationRequest optimization,
                                  boolean githubEnabled, boolean githubKeyAvailable) {}
