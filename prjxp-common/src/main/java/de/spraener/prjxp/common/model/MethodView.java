package de.spraener.prjxp.common.model;

public record MethodView(
        String fqn,        // "com.example.Foo#bar" (symbol_fqn) or class FQN for class-level hits
        String file,
        Integer lineFrom,  // 1-based source lines of the method body (null when unknown)
        Integer lineTo,
        String javadoc,    // null when absent
        String body)       // complete implementation, original lines
{ }
