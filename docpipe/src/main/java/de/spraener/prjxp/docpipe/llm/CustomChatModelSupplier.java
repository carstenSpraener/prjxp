package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.java.Log;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Log
public class CustomChatModelSupplier implements ChatModelSupplier, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public boolean canProvide(DPModelConfig cfg) {
        if( !cfg.getServerType().equals(ServerTypes.CUSTOM.serverType()) ) {
            return false;
        }
        if( !StringUtils.hasText(cfg.getKiChatImpl()) ) {
            log.warning("Configured custom model-access "+cfg+" is missing implementation class!");
            return false;
        }
        if( classIsAccessible(cfg.getKiChatImpl()) ) {
            log.warning("Configured custom model-access "+cfg+" can not be instantiated.");
            return false;
        }
        return true;
    }

    private boolean classIsAccessible(String kiChatImpl) {
        try {
            Class implClazz = Class.forName(kiChatImpl);
            return applicationContext.getBeanNamesForType(implClazz).length>0 || implClazz.getConstructor(DPModelConfig.class) != null;
        } catch( ReflectiveOperationException | SecurityException roXC ) {
            log.warning("Trying to access configured custom model-access creates error: "+roXC.getMessage());
            return false;
        }
    }

    @Override
    public ChatModel provide(DPModelConfig cfg) {
        try {
            Class<? extends ChatModel> cmClazz = (Class<? extends ChatModel>)Class.forName(cfg.getKiChatImpl());
            String[] beanNames = applicationContext.getBeanNamesForType(cmClazz);
            if( beanNames.length==0 ) {
                return cmClazz.getConstructor(DPModelConfig.class).newInstance(cfg);
            } else {
                if( beanNames.length == 1 ) {
                    return applicationContext.getBean(beanNames[0], ChatModel.class);
                } else {
                    throw new IllegalStateException("The spring application context contains multiple beans of type "+cmClazz+". Expecting only one.");
                }
            }
        } catch( ReflectiveOperationException roXC ) {
            log.severe("Could not instantiate custom chat model "+cfg.getKiChatImpl()+": "+roXC.getMessage());
            throw new IllegalStateException(roXC);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
