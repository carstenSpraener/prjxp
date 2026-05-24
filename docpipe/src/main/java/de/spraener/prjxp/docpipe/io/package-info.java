

/**
 * <p>Provides an abstraction layer for text-based file output operations, designed to enhance testability and simplify resource management within the document processing pipeline.</p>
 *
 * <p>This package decouples output generation from concrete file I/O by introducing a unified contract for writing text data. The core architecture revolves around three main components:</p>
 * <ul>
 *   <li>{@link OutputSink} - The primary interface that defines methods for writing formatted text and managing the output lifecycle. It extends {@link AutoCloseable} to support try-with-resources blocks.</li>
 *   <li>{@link FileOutputSink} - The default production implementation that handles actual disk I/O. It ensures UTF-8 encoding, automatic parent directory creation, and buffered writing via {@link java.io.PrintWriter}.</li>
 *   <li>{@link OutputSinkFactory} - A Spring-managed service that serves as the central entry point for instantiating {@code OutputSink} objects. It abstracts creation logic, enabling seamless substitution with mock or in-memory implementations during unit and integration testing.</li>
 * </ul>
 *
 * <p><b>Primary Use Cases:</b></p>
 * <ul>
 *   <li><b>Production Output:</b> Inject {@link OutputSinkFactory} and call {@code createSink(Path)} or {@code createSink(String)} to obtain a sink for writing processed documents or logs to disk.</li>
 *   <li><b>Testing &amp; Mocking:</b> Replace the factory or directly mock {@link OutputSink} to capture output in memory, verify written content, and avoid filesystem dependencies.</li>
 *   <li><b>Resource Management:</b> Utilize try-with-resources syntax to ensure sinks are properly closed and underlying streams are flushed after use.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.io;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

