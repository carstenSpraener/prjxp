package de.spraener.prjxp.docpipe;

/** Thrown by DocPipeRunner.runForProject when one or more content-creation tasks failed. Never calls System.exit (in-process use). */
public class DocPipeRunException extends RuntimeException {
    public DocPipeRunException(String message) { super(message); }
}
