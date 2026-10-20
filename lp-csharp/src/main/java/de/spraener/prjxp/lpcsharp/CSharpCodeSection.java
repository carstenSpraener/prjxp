package de.spraener.prjxp.lpcsharp;

/**
 * The logical sections a C# source file is split into by the {@code CSharpCodeChunker}.
 *
 * <p>Stored in the chunk metadata under {@link #METADATA_KEY}. Kept local to the C#
 * pack (not in prjxp-common) because both its producer (the chunker) and consumer
 * (the retriever, Phase 04) live in this plugin.
 */
public enum CSharpCodeSection {
    UNKNOWN("unknown"),
    /** using directives + namespace header (analog of Java imports). */
    USINGS("usings"),
    /** The type skeleton: declaration line + member signatures (no bodies). */
    TYPE_FRAME("typeFrame"),
    /** A method implementation (body included). */
    METHOD("method"),
    /** A constructor (body included). */
    CONSTRUCTOR("constructor"),
    /** Whole-file fallback when parsing fails (line-based parts). */
    RAW_FILE("rawFile"),
    ;

    /** Metadata key under which the section name is stored on a chunk. */
    public static final String METADATA_KEY = "csharp_code_section";

    private final String name;

    CSharpCodeSection(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static CSharpCodeSection fromName(String name) {
        for (CSharpCodeSection section : values()) {
            if (section.getName().equals(name)) {
                return section;
            }
        }
        return UNKNOWN;
    }
}
