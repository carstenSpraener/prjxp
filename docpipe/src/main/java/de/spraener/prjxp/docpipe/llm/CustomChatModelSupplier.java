package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.DPLogMessage;
import de.spraener.prjxp.docpipe.DPLogService;
import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.logging.Level;

@Component
@Log
@RequiredArgsConstructor
public class CustomChatModelSupplier implements ChatModelSupplier {
    private final List<CustomChatModel> customChatModelList;
    private final DPLogService logService;

    @Override
    public boolean canProvide(DPModelConfig cfg) {
        if( !cfg.getServerType().equals(ServerTypes.CUSTOM.serverType()) ) {
            return false;
        }
        return customChatModelList.stream()
                .filter( cm -> cm.canHandle(cfg))
                .findFirst()
                .isPresent();
    }

    @Override
    public ChatModel provide(DPModelConfig cfg) {
        return customChatModelList.stream()
                .filter(cm->cm.canHandle(cfg))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("There is now chat model defined for config: "+cfg));
    }
}
