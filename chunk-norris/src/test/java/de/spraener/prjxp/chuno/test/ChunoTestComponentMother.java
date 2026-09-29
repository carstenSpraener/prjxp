package de.spraener.prjxp.chuno.test;

import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.chuno.ChunkerFactory;
import de.spraener.prjxp.chuno.code.java.JavaCodeChunker;
import de.spraener.prjxp.chuno.code.java.JavaDependenciesChunker;
import de.spraener.prjxp.chuno.code.java.JavaDependencyHandler;
import de.spraener.prjxp.chuno.code.java.JavaFQNamesMapper;
import de.spraener.prjxp.chuno.code.typescript.TypeScriptCodeChunker;
import de.spraener.prjxp.chuno.docs.DocConversionRouter;
import de.spraener.prjxp.chuno.docs.MarkdownChunker;
import de.spraener.prjxp.chuno.docs.MetaInfReader;
import de.spraener.prjxp.chuno.docs.Image2MDConversionAgent;
import de.spraener.prjxp.chuno.docs.config.ConversionRoute;
import de.spraener.prjxp.chuno.docs.config.ConversionRoutesConfig;
import de.spraener.prjxp.chuno.docs.html.Html2MDConversionAgent;
import de.spraener.prjxp.chuno.docs.pdf.Pdf2ImageConversionAgent;
import de.spraener.prjxp.chuno.docs.pdf.Pdf2MDWithTikaDocConversionAgent;
import de.spraener.prjxp.chuno.docs.pdf.Pdf2TextConversionAgent;
import de.spraener.prjxp.chuno.docs.poi.Doc2HtmlConversionAgent;
import de.spraener.prjxp.chuno.docs.poi.Docx2HtmlConversionAgent;
import de.spraener.prjxp.chuno.docs.rtf.RtfToHtmlAgent;
import de.spraener.prjxp.chuno.docs.txt.Text2MDConversionAgent;
import de.spraener.prjxp.chuno.docs.txt.TextChunker;
import de.spraener.prjxp.chuno.util.DependencyRegistryManager;
import de.spraener.prjxp.chuno.veto.StandardVetos;
import de.spraener.prjxp.chuno.veto.VetoRegistry;

import java.util.function.Consumer;

public final class ChunoTestComponentMother {
    private ChunoTestComponentMother() {}

    public static final <T> T create(Consumer<T>... modifiers) {
        return (T) org.mockito.Mockito.mock(Object.class);
    }

    public static final ChunkProcess createChunkProcess(Consumer<ChunkProcess>... modifiers) {
        ChunkProcess mock = org.mockito.Mockito.mock(ChunkProcess.class);
        if (modifiers != null) {
            for (Consumer<ChunkProcess> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final StandardVetos createStandardVetos(Consumer<StandardVetos>... modifiers) {
        StandardVetos mock = org.mockito.Mockito.mock(StandardVetos.class);
        if (modifiers != null) {
            for (Consumer<StandardVetos> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final VetoRegistry createVetoRegistry(Consumer<VetoRegistry>... modifiers) {
        VetoRegistry mock = org.mockito.Mockito.mock(VetoRegistry.class);
        if (modifiers != null) {
            for (Consumer<VetoRegistry> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final DependencyRegistryManager createDependencyRegistryManager(Consumer<DependencyRegistryManager>... modifiers) {
        DependencyRegistryManager mock = org.mockito.Mockito.mock(DependencyRegistryManager.class);
        if (modifiers != null) {
            for (Consumer<DependencyRegistryManager> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final DocConversionRouter createDocConversionRouter(Consumer<DocConversionRouter>... modifiers) {
        DocConversionRouter mock = org.mockito.Mockito.mock(DocConversionRouter.class);
        if (modifiers != null) {
            for (Consumer<DocConversionRouter> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final ChunkerFactory createChunkerFactory(Consumer<ChunkerFactory>... modifiers) {
        ChunkerFactory mock = org.mockito.Mockito.mock(ChunkerFactory.class);
        if (modifiers != null) {
            for (Consumer<ChunkerFactory> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final JavaDependencyHandler createJavaDependencyHandler(Consumer<JavaDependencyHandler>... modifiers) {
        JavaDependencyHandler mock = org.mockito.Mockito.mock(JavaDependencyHandler.class);
        if (modifiers != null) {
            for (Consumer<JavaDependencyHandler> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final Image2MDConversionAgent createImage2MDConversionAgent(Consumer<Image2MDConversionAgent>... modifiers) {
        Image2MDConversionAgent mock = org.mockito.Mockito.mock(Image2MDConversionAgent.class);
        if (modifiers != null) {
            for (Consumer<Image2MDConversionAgent> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final JavaFQNamesMapper createJavaFQNamesMapper(Consumer<JavaFQNamesMapper>... modifiers) {
        JavaFQNamesMapper mock = org.mockito.Mockito.mock(JavaFQNamesMapper.class);
        if (modifiers != null) {
            for (Consumer<JavaFQNamesMapper> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final MarkdownChunker createMarkdownChunker(Consumer<MarkdownChunker>... modifiers) {
        MarkdownChunker mock = org.mockito.Mockito.mock(MarkdownChunker.class);
        if (modifiers != null) {
            for (Consumer<MarkdownChunker> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final TextChunker createTextChunker(Consumer<TextChunker>... modifiers) {
        TextChunker mock = org.mockito.Mockito.mock(TextChunker.class);
        if (modifiers != null) {
            for (Consumer<TextChunker> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final Doc2HtmlConversionAgent createDoc2HtmlConversionAgent(Consumer<Doc2HtmlConversionAgent>... modifiers) {
        Doc2HtmlConversionAgent mock = org.mockito.Mockito.mock(Doc2HtmlConversionAgent.class);
        if (modifiers != null) {
            for (Consumer<Doc2HtmlConversionAgent> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final Docx2HtmlConversionAgent createDocx2HtmlConversionAgent(Consumer<Docx2HtmlConversionAgent>... modifiers) {
        Docx2HtmlConversionAgent mock = org.mockito.Mockito.mock(Docx2HtmlConversionAgent.class);
        if (modifiers != null) {
            for (Consumer<Docx2HtmlConversionAgent> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final Pdf2ImageConversionAgent createPdf2ImageConversionAgent(Consumer<Pdf2ImageConversionAgent>... modifiers) {
        Pdf2ImageConversionAgent mock = org.mockito.Mockito.mock(Pdf2ImageConversionAgent.class);
        if (modifiers != null) {
            for (Consumer<Pdf2ImageConversionAgent> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final Pdf2MDWithTikaDocConversionAgent createPdf2MDWithTikaDocConversionAgent(Consumer<Pdf2MDWithTikaDocConversionAgent>... modifiers) {
        Pdf2MDWithTikaDocConversionAgent mock = org.mockito.Mockito.mock(Pdf2MDWithTikaDocConversionAgent.class);
        if (modifiers != null) {
            for (Consumer<Pdf2MDWithTikaDocConversionAgent> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final Pdf2TextConversionAgent createPdf2TextConversionAgent(Consumer<Pdf2TextConversionAgent>... modifiers) {
        Pdf2TextConversionAgent mock = org.mockito.Mockito.mock(Pdf2TextConversionAgent.class);
        if (modifiers != null) {
            for (Consumer<Pdf2TextConversionAgent> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final Text2MDConversionAgent createText2MDConversionAgent(Consumer<Text2MDConversionAgent>... modifiers) {
        Text2MDConversionAgent mock = org.mockito.Mockito.mock(Text2MDConversionAgent.class);
        if (modifiers != null) {
            for (Consumer<Text2MDConversionAgent> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final RtfToHtmlAgent createRtfToHtmlAgent(Consumer<RtfToHtmlAgent>... modifiers) {
        RtfToHtmlAgent mock = org.mockito.Mockito.mock(RtfToHtmlAgent.class);
        if (modifiers != null) {
            for (Consumer<RtfToHtmlAgent> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final Html2MDConversionAgent createHtml2MDConversionAgent(Consumer<Html2MDConversionAgent>... modifiers) {
        Html2MDConversionAgent mock = org.mockito.Mockito.mock(Html2MDConversionAgent.class);
        if (modifiers != null) {
            for (Consumer<Html2MDConversionAgent> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final MetaInfReader createMetaInfReader(Consumer<MetaInfReader>... modifiers) {
        MetaInfReader mock = org.mockito.Mockito.mock(MetaInfReader.class);
        if (modifiers != null) {
            for (Consumer<MetaInfReader> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final ConversionRoute createConversionRoute(Consumer<ConversionRoute>... modifiers) {
        ConversionRoute mock = org.mockito.Mockito.mock(ConversionRoute.class);
        if (modifiers != null) {
            for (Consumer<ConversionRoute> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final ConversionRoutesConfig createConversionRoutesConfig(Consumer<ConversionRoutesConfig>... modifiers) {
        ConversionRoutesConfig mock = org.mockito.Mockito.mock(ConversionRoutesConfig.class);
        if (modifiers != null) {
            for (Consumer<ConversionRoutesConfig> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final TypeScriptCodeChunker createTypeScriptCodeChunker(Consumer<TypeScriptCodeChunker>... modifiers) {
        TypeScriptCodeChunker mock = org.mockito.Mockito.mock(TypeScriptCodeChunker.class);
        if (modifiers != null) {
            for (Consumer<TypeScriptCodeChunker> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final JavaCodeChunker createJavaCodeChunker(Consumer<JavaCodeChunker>... modifiers) {
        JavaCodeChunker mock = org.mockito.Mockito.mock(JavaCodeChunker.class);
        if (modifiers != null) {
            for (Consumer<JavaCodeChunker> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }

    public static final JavaDependenciesChunker createJavaDependenciesChunker(Consumer<JavaDependenciesChunker>... modifiers) {
        JavaDependenciesChunker mock = org.mockito.Mockito.mock(JavaDependenciesChunker.class);
        if (modifiers != null) {
            for (Consumer<JavaDependenciesChunker> modifier : modifiers) {
                modifier.accept(mock);
            }
        }
        return mock;
    }
}
