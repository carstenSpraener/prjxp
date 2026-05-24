package de.spraener.prjxp.docpipe;

import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.stream.Stream;

@Service
@Log
/**
 * Service for managing and aggregating log messages within the DocPipe pipeline.
 * <p>
 * This service allows logging of {@link DPLogMessage}s and maintains a list of 
 * warning and severe messages to provide a summary at the end of the execution.
 * </p>
 */
public class DPLogService {
    private final List<DPLogMessage> errorMessageList = new CopyOnWriteArrayList<>();

    /**
     * Logs a message and adds it to the error list if its level is WARNING or higher.
     *
     * @param msg the log message to record
     * @return this service instance for chaining
     */
    public DPLogService logMessage(DPLogMessage msg) {
        log.log(msg.getLevel(), msg.getMessage());
        if (msg.getLevel().intValue() >= Level.WARNING.intValue()) {
            errorMessageList.add(msg);
        }
        return this;
    }

    /**
     * Returns the highest log level among all recorded warning and severe messages.
     *
     * @return the maximum {@link Level} found, or {@link Level#FINEST} if no messages were recorded
     */
    public Level maxLevel() {
        return errorMessageList.stream()
                .map(DPLogMessage::getLevel)
                .max(Comparator.comparingInt(Level::intValue))
                .orElse(Level.FINEST);
    }

    /**
     * Returns a stream of log messages that have at least the specified minimum level.
     *
     * @param minLevel the minimum {@link Level} to filter by
     * @return a stream of matching {@link DPLogMessage}s
     */
    public Stream<DPLogMessage> getMessagesWithLevelMin(Level minLevel) {
        return errorMessageList.stream()
                .filter(m -> m.getLevel().intValue() >= minLevel.intValue());
    }
}
