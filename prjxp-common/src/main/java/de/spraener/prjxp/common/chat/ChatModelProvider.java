package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
public class ChatModelProvider implements Function<Predicate<PrjXPChatModelReference>,Optional<KIChat>> {
    private final List<KIChat> chatModels;

    @Override
    public Optional<KIChat> apply(Predicate<PrjXPChatModelReference> p) {
        for( var model : chatModels){
            if( p.test(model.getChatModelReference())){
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }

    public Optional<KIChat> get(String name){
        return apply(m->m.getModelName().equals(name));
    }
}
