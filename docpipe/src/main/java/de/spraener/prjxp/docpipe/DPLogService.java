package de.spraener.prjxp.docpipe;

import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.stream.Stream;

@Service
@Log
public class DPLogService {
    private final List<DPLogMessage> errorMessageList = new CopyOnWriteArrayList<>();

    public DPLogService logMessage(DPLogMessage msg) {
        log.log(msg.getLevel(), msg.getMessage());
        if (msg.getLevel().intValue() >= Level.WARNING.intValue()) {
            errorMessageList.add(msg);
        }
        return this;
    }

    public Level maxLevel() {
        return errorMessageList.stream()
                .map(DPLogMessage::getLevel)
                .max(Comparator.comparingInt(Level::intValue))
                .orElse(Level.FINEST);
    }

    public Stream<DPLogMessage> getMessagesWithLevelMin(Level minLevel) {
        return errorMessageList.stream()
                .filter(m -> m.getLevel().intValue() >= minLevel.intValue());
    }
}
