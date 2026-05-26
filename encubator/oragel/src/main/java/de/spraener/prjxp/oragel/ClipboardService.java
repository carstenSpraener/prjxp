package de.spraener.prjxp.oragel;

import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.oragel.events.EnrichedPromptEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;

@Component
@RequiredArgsConstructor
@Log
public class ClipboardService {
    private final PxLogService logService;

    public ClipboardService() {
        log.info("ClipboardService wurde initialisiert");
    }

    @EventListener
    public void copyToClipboard(EnrichedPromptEvent event) {
        log.info("ClipboardService wurde aufgerufen mit event-Class" + event.getClass());
        try {
            StringSelection selection = new StringSelection(event.getContent());
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, selection);
        } catch (Exception e) {
            logService.error(e, "Fehler beim Zugriff auf die Zwischenablage: %s", e.getMessage());
        }
    }
}
