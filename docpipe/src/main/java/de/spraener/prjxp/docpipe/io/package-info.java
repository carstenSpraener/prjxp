

/**
 * Provides an abstraction layer for writing output data to various destinations, primarily focusing on file-based I/O.
 * <p>
 * This package is designed to decouple business logic from concrete file system operations, enhancing testability and maintainability.
 * By relying on the {@link OutputSink} interface, client code can write formatted text without being tightly coupled to specific I/O implementations.
 * </p>
 * <p><b>Architecture & Class Interactions:</b></p>
 * <ul>
 *   <li>{@link OutputSink}: The core interface extending {@code AutoCloseable}. It defines methods for writing lines and formatted strings, enabling safe resource management via try-with-resources blocks.</li>
 *   <li>{@link FileOutputSink}: The default concrete implementation that handles actual file I/O. It automatically creates parent directories and ensures UTF-8 encoding using a buffered {@code PrintWriter}.</li>
 *   <li>{@link OutputSinkFactory}: A Spring-managed factory service responsible for instantiating {@code OutputSink} instances. It abstracts object creation, allowing seamless substitution with mock or in-memory implementations during testing.</li>
 * </ul>
 * <p><b>Primary Use Cases:</b></p>
 * <ul>
 *   <li>Inject {@link OutputSinkFactory} into a Spring component.</li>
 *   <li>Request an {@code OutputSink} via {@link OutputSinkFactory#createSink(Path)} or its String variant.</li>
 *   <li>Utilize the sink within a try-with-resources block to write formatted output safely.</li>
 *   <li>Replace the factory or sink implementation in test environments to avoid disk I/O and improve test speed.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.io;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

