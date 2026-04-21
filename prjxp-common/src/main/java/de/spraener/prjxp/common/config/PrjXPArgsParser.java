package de.spraener.prjxp.common.config;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@Data
@Log
@RequiredArgsConstructor
public class PrjXPArgsParser {
    private final PrjXPConfig cfg;
    private final ApplicationEventPublisher publisher;

    public PrjXPConfig parse(String[] args) {
        publisher.publishEvent(new CliArgsParsingEvent(args, cfg));
        return cfg;
    }


}
