package de.spraener.prjxp.chuno.spring;

import de.spraener.prjxp.common.config.PrjXPConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Configuration
public class SpringConfig {

    @Bean
    public Function<String, ChatModel> chatModelFactory(PrjXPConfig cfg) {
        return new Function<String, ChatModel>() {
            private static Map<String, ChatModel> modelMap = new HashMap<>();

            @Override
            public ChatModel apply(String modelName) {
                ChatModel model = modelMap.get(modelName);
                if (model == null) {
                    model = OllamaChatModel.builder()
                            .modelName(modelName)
                            .baseUrl(cfg.getChatApiUrl())
                            .build();
                    modelMap.put(modelName, model);
                }
                return model;
            }
        };
    }
}
