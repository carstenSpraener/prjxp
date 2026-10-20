package com.example.lib;

/**
 * Test fixture: a "private library" bundled into the plugin JAR.
 * Incompatible version (v2) of the same class that privlib-a bundles —
 * the two plugins must each see their own copy.
 */
public class Lib {
    public static String version() {
        return "v2";
    }
}
