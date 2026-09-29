package de.spraener.prjxp.common.test;

import de.spraener.prjxp.common.chat.KIChatProvider;
import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.chat.OpenAPISupplier;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.PrjXPCli;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.chat.GeminiSupplier;
import de.spraener.prjxp.common.chat.LMStudioSupplier;
import de.spraener.prjxp.common.chat.OllamaSupplier;
import de.spraener.prjxp.common.chat.CustomChatModelSupplier;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.PrjXPArgsParser;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.util.SpringContextSupplier;
import de.spraener.prjxp.common.util.BeanNameFinder;
import de.spraener.prjxp.common.scripting.ScriptCompileService;

import java.util.function.Consumer;

public final class PrjXPTestComponentMother {
    private PrjXPTestComponentMother() {}

    public static final <T> T create(Consumer<T>... modifiers) {
        return (T) org.mockito.Mockito.mock(Object.class);
    }

    public static final PrjXPConfig createPrjXPConfig(Consumer<PrjXPConfig>... modifiers) {
        PrjXPConfig mock = org.mockito.Mockito.mock(PrjXPConfig.class);
        if (modifiers != null) {
            for (Consumer<PrjXPConfig> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final KIChatProvider createKIChatProvider(Consumer<KIChatProvider>... modifiers) {
        KIChatProvider mock = org.mockito.Mockito.mock(KIChatProvider.class);
        if (modifiers != null) {
            for (Consumer<KIChatProvider> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final KIChatModelProvider createKIChatModelProvider(Consumer<KIChatModelProvider>... modifiers) {
        KIChatModelProvider mock = org.mockito.Mockito.mock(KIChatModelProvider.class);
        if (modifiers != null) {
            for (Consumer<KIChatModelProvider> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final OpenAPISupplier createOpenAPISupplier(Consumer<OpenAPISupplier>... modifiers) {
        OpenAPISupplier mock = org.mockito.Mockito.mock(OpenAPISupplier.class);
        if (modifiers != null) {
            for (Consumer<OpenAPISupplier> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final PxChunkDaoProvider createPxChunkDaoProvider(Consumer<PxChunkDaoProvider>... modifiers) {
        PxChunkDaoProvider mock = org.mockito.Mockito.mock(PxChunkDaoProvider.class);
        if (modifiers != null) {
            for (Consumer<PxChunkDaoProvider> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final PrjXPCli createPrjXPCli(Consumer<PrjXPCli>... modifiers) {
        PrjXPCli mock = org.mockito.Mockito.mock(PrjXPCli.class);
        if (modifiers != null) {
            for (Consumer<PrjXPCli> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final GeminiSupplier createGeminiSupplier(Consumer<GeminiSupplier>... modifiers) {
        GeminiSupplier mock = org.mockito.Mockito.mock(GeminiSupplier.class);
        if (modifiers != null) {
            for (Consumer<GeminiSupplier> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final LMStudioSupplier createLMStudioSupplier(Consumer<LMStudioSupplier>... modifiers) {
        LMStudioSupplier mock = org.mockito.Mockito.mock(LMStudioSupplier.class);
        if (modifiers != null) {
            for (Consumer<LMStudioSupplier> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final OllamaSupplier createOllamaSupplier(Consumer<OllamaSupplier>... modifiers) {
        OllamaSupplier mock = org.mockito.Mockito.mock(OllamaSupplier.class);
        if (modifiers != null) {
            for (Consumer<OllamaSupplier> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final CustomChatModelSupplier createCustomChatModelSupplier(Consumer<CustomChatModelSupplier>... modifiers) {
        CustomChatModelSupplier mock = org.mockito.Mockito.mock(CustomChatModelSupplier.class);
        if (modifiers != null) {
            for (Consumer<CustomChatModelSupplier> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final PrjXPJsonStreamProvider createPrjXPJsonStreamProvider(Consumer<PrjXPJsonStreamProvider>... modifiers) {
        PrjXPJsonStreamProvider mock = org.mockito.Mockito.mock(PrjXPJsonStreamProvider.class);
        if (modifiers != null) {
            for (Consumer<PrjXPJsonStreamProvider> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final PrjXPArgsParser createPrjXPArgsParser(Consumer<PrjXPArgsParser>... modifiers) {
        PrjXPArgsParser mock = org.mockito.Mockito.mock(PrjXPArgsParser.class);
        if (modifiers != null) {
            for (Consumer<PrjXPArgsParser> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final PxLogService createPxLogService(Consumer<PxLogService>... modifiers) {
        PxLogService mock = org.mockito.Mockito.mock(PxLogService.class);
        if (modifiers != null) {
            for (Consumer<PxLogService> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final SpringContextSupplier createSpringContextSupplier(Consumer<SpringContextSupplier>... modifiers) {
        SpringContextSupplier mock = org.mockito.Mockito.mock(SpringContextSupplier.class);
        if (modifiers != null) {
            for (Consumer<SpringContextSupplier> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final BeanNameFinder createBeanNameFinder(Consumer<BeanNameFinder>... modifiers) {
        BeanNameFinder mock = org.mockito.Mockito.mock(BeanNameFinder.class);
        if (modifiers != null) {
            for (Consumer<BeanNameFinder> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final ScriptCompileService createScriptCompileService(Consumer<ScriptCompileService>... modifiers) {
        ScriptCompileService mock = org.mockito.Mockito.mock(ScriptCompileService.class);
        if (modifiers != null) {
            for (Consumer<ScriptCompileService> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }
}
