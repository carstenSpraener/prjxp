/**
 * Provides output abstractions for the document pipeline.
 *
 * <p>
 * This package isolates file-writing concerns behind the {@link de.spraener.prjxp.docpipe.io.OutputSink}
 * interface. Components that generate documents or other textual output can depend on this abstraction
 * instead of directly using filesystem APIs or {@link java.io.PrintWriter}. This improves testability,
 * allows alternative output implementations, and centralizes the creation of file-based sinks.
 * </p>
 *
 * <p>
 * The main types are:
 * </p>
 * <ul>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.io.OutputSink} defines the minimal output contract for writing
 *     lines and formatted text, and supports try-with-resources through {@link java.lang.AutoCloseable}.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.io.FileOutputSink} is the default filesystem-backed implementation.
 *     It creates required parent directories and writes UTF-8 encoded text to the target file.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.docpipe.io.OutputSinkFactory} is a Spring service responsible for creating
 *     {@code OutputSink} instances from {@link java.nio.file.Path} or {@link java.lang.String} destinations.
 *   </li>
 * </ul>
 *
 * <p>
 * Typical clients obtain an {@code OutputSink} from {@code OutputSinkFactory}, write content using
 * {@code println} and {@code printf}, and close the sink when finished, preferably with a
 * try-with-resources block. Tests can replace the factory or the sink implementation with in-memory
 * or mock variants to avoid actual file I/O.
 * </p>
 */
package de.spraener.prjxp.docpipe.io;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

