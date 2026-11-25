package de.spraener.prjxp.lpgdscript;

public enum GdscriptCodeSection {
    UNKNOWN("unknown"),
    HEADER("header"),        // class_name, extends, @tool, @icon, ## class docs (preamble)
    FIELDS("fields"),        // var/const/enum/signal/@export/@onready/static/preload runs
    FUNC("func"),            // a function (signature + body)
    FUNC_DOC("funcDoc"),     // ## docs above a func (separate chunk, like Java .javadoc)
    INNER_CLASS("innerClass"),// a `class X:` block — TRANSIENT: expanded into its inner sections (header/fields/func) during partitioning; never emitted as a final chunk
    MISC("misc"),            // other top-level lines (losslessness catch-all)
    RAW_FILE("rawFile");     // whole-file fallback
    public static final String METADATA_KEY = "gdscript_code_section";
    private final String name;
    GdscriptCodeSection(String name){ this.name = name; }
    public String getName(){ return name; }
    public static GdscriptCodeSection fromName(String n){
        for (var s : values()) if (s.getName().equals(n)) return s;
        return UNKNOWN;
    }
}
