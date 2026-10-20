package com.example.lib;

/**
 * Test fixture: a "private library" bundled into the plugin JAR.
 * The privlib-a and privlib-b fixtures bundle incompatible versions of
 * this same class to prove child-first version isolation.
 */
public class Lib {
    public static String version() {
        return "v1";
    }
}
