package de.spraener.prjxp.docpipe;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.logging.Level;

@Data
@RequiredArgsConstructor
/**
 * Represents a log message within the DocPipe pipeline.
 * <p>
 * This class encapsulates a logging level and the corresponding message text, 
 * allowing for structured error reporting across different components.
 * </p>
 */
public class DPLogMessage {
    private final Level level;
    private final String message;
}
