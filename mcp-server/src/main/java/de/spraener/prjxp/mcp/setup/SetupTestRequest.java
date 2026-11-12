package de.spraener.prjxp.mcp.setup;
public record SetupTestRequest<T>(@jakarta.validation.constraints.NotBlank String configVersion,
                                  @jakarta.validation.Valid @jakarta.validation.constraints.NotNull T configuration) {}
