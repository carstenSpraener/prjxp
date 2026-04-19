package de.spraener.prjxp.gldrtrvr.javadoc;

import lombok.extern.java.Log;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Log
public class JavdocGeneratedPostProcessor {

    @EventListener
    public void javadocGenerated(JavaDocGeneratedEvent evt) {
        log.info("JavaDoc für Chunk %s generiert: %s".formatted(evt.pxChunkID(), evt.javadoc()));
    }
}
