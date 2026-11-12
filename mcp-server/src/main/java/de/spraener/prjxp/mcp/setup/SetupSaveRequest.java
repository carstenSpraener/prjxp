package de.spraener.prjxp.mcp.setup;
public record SetupSaveRequest(@jakarta.validation.constraints.NotBlank String configVersion,
                                @jakarta.validation.constraints.NotBlank String step, boolean skip, String testId,
                                @jakarta.validation.Valid EmbeddingConfigRequest embedding,
                                @jakarta.validation.Valid ChatModelRequest chat,
                                @jakarta.validation.Valid GithubConfigRequest github,
                                @jakarta.validation.Valid OptimizationRequest optimization) {}
