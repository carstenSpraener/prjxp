package de.spraener.prjxp.docpipe;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.logging.Level;

@Data
@RequiredArgsConstructor
public class DPLogMessage {
    private final Level level;
    private final String message;
}
