package de.spraener.prjxp.common.test;

import de.spraener.prjxp.common.config.CliArgsParsingEvent;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;

import java.util.function.Consumer;

public final class PrjXPTestObjectMother {
    private PrjXPTestObjectMother() {}

    @SafeVarargs
    public static final ProjectDefinition createProjectDefinition(Consumer<ProjectDefinition>... modifiers) {
        ProjectDefinition obj = new ProjectDefinition();
        if (modifiers != null) {
            for (Consumer<ProjectDefinition> modifier : modifiers) {
                modifier.accept(obj);
            }
        }
        return obj;
    }

    @SafeVarargs
    public static final PrjXPChatModelReference createPrjXPChatModelReference(Consumer<PrjXPChatModelReference>... modifiers) {
        PrjXPChatModelReference obj = new PrjXPChatModelReference();
        if (modifiers != null) {
            for (Consumer<PrjXPChatModelReference> modifier : modifiers) {
                modifier.accept(obj);
            }
        }
        return obj;
    }

    @SafeVarargs
    public static final PrjXPEmbeddingStoreReference createPrjXPEmbeddingStoreReference(Consumer<PrjXPEmbeddingStoreReference>... modifiers) {
        PrjXPEmbeddingStoreReference obj = new PrjXPEmbeddingStoreReference();
        if (modifiers != null) {
            for (Consumer<PrjXPEmbeddingStoreReference> modifier : modifiers) {
                modifier.accept(obj);
            }
        }
        return obj;
    }

    @SafeVarargs
    public static final CliArgsParsingEvent createCliArgsParsingEvent(Consumer<CliArgsParsingEvent>... modifiers) {
        CliArgsParsingEvent obj = new CliArgsParsingEvent(new String[0], null);
        if (modifiers != null) {
            for (Consumer<CliArgsParsingEvent> modifier : modifiers) {
                modifier.accept(obj);
            }
        }
        return obj;
    }

    public static final PxChunk createPxChunk(Consumer<PxChunk>... modifiers) {
        return PxChunk.create(modifiers);
    }

    public static final PrjXPConfig createPrjXPConfig(Consumer<PrjXPConfig>... modifiers) {
        PrjXPConfig obj = new PrjXPConfig();
        if (modifiers != null) {
            for (Consumer<PrjXPConfig> modifier : modifiers) {
                modifier.accept(obj);
            }
        }
        return obj;
    }
}
