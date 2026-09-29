package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.ChunkerSearchCapabilitiesProvider;
import de.spraener.prjxp.common.capability.IdentifierRules;
import de.spraener.prjxp.common.capability.LanguageCapability;
import de.spraener.prjxp.common.capability.SearchParamDef;
import de.spraener.prjxp.common.code.java.JavaCodeSection;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class JavaSearchCapabilitiesProvider implements ChunkerSearchCapabilitiesProvider {

    public static final String JAVA_MIME_TYPE = "text/x-java-code";
    private static final String LANGUAGE = "java";

    @Override
    public String language() {
        return LANGUAGE;
    }

    @Override
    public LanguageCapability capability() {
        return new LanguageCapability(
                LANGUAGE,
                List.of(JAVA_MIME_TYPE),
                symbolTypes(),
                params(),
                new IdentifierRules("[A-Za-z_$][A-Za-z0-9_$]*", true));
    }

    private List<String> symbolTypes() {
        return Arrays.stream(JavaCodeSection.values())
                .filter(section -> section != JavaCodeSection.UNKNOWN)
                .map(JavaCodeSection::getName)
                .toList();
    }

    private List<SearchParamDef> params() {
        return List.of(
                SearchParamDef.optional("fqn", "Fully qualified name of a class or method (e.g. 'com.example.Foo#bar')."),
                SearchParamDef.optional("methodName", "Name of a method."),
                SearchParamDef.optional("signatureHash", "Hash of a method signature for exact matching."),
                SearchParamDef.optional("containerFqn", "Fully qualified name of the containing class."),
                SearchParamDef.optional("symbolType", "Filter by symbol type. Valid values: " + String.join(", ", symbolTypes()) + "."));
    }
}
