package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.docpipe.DPLogService;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Log
@RequiredArgsConstructor
public class CustomChatModelSupplier implements ChatModelSupplier {
    private final List<CustomChatModel> customChatModelList;
    private final DPLogService logService;

    @Override
    public boolean canProvide(PrjXPChatModelReference cmRef) {
        if( !cmRef.getServerType().equals(ServerTypes.CUSTOM.serverType()) ) {
            return false;
        }
        return customChatModelList.stream()
                .filter( cm -> cm.canHandle(cmRef))
                .findFirst()
                .isPresent();
    }

    @Override
    public ChatModel provide(PrjXPChatModelReference cmRef) {
        return customChatModelList.stream()
                .filter(cm->cm.canHandle(cmRef))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("There is now chat model defined for config: "+cmRef));
    }
}
