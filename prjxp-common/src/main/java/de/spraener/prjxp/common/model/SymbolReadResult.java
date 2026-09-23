package de.spraener.prjxp.common.model;

import java.util.List;

public record SymbolReadResult(
        List<MethodView> matches,     // max 10 full matches
        List<String> remainingFqns,   // distinct fqns of matches beyond the 10
        String error) {               // null when OK

    public static SymbolReadResult error(String message) {
        return new SymbolReadResult(List.of(), List.of(), message);
    }
}
