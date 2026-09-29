package de.spraener.prjxp.chuno;

import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferEncryptMode;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkProcessTest {
    @TempDir
    Path tempDir;

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureConsole() {
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true));
    }

    @AfterEach
    void restoreConsole() {
        System.setOut(originalOut);
    }

    private PrjXPConfig configWith(String passwordEnvKey) throws Exception {
        Path rootDir = tempDir.resolve("src");
        Files.createDirectories(rootDir);
        Files.writeString(rootDir.resolve("src.txt"), "some source text");

        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("cwd");
        pd.setRootDir(rootDir.toString());
        pd.setJsonlFile(tempDir.resolve("out.jsonl").toString());

        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setActiveProject("cwd");
        cfg.getProjects().add(pd);
        cfg.getTransfer().setPasswordEnv(passwordEnvKey);
        return cfg;
    }

    private ChunkProcess uut(PrjXPConfig cfg, String passwordValue) {
        return new ChunkProcess(
                Mockito.mock(PxLogService.class),
                stubFactory(),
                Mockito.mock(ApplicationEventPublisher.class),
                noVeto(),
                cfg,
                resolverWith(cfg.getTransfer().getPasswordEnv(), passwordValue)
        );
    }

    private String decryptedOutput(PrjXPConfig cfg, char[] password) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(cfg.getActiveProject().orElseThrow().getJsonlFile()));
        try (InputStream in = TransferCrypto.openDecryptedInputStream(new ByteArrayInputStream(bytes), password)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void execute_withoutPassword_writesPlaintextJsonl() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_NO_PASSWORD_XYZ");

        uut(cfg, null).execute();

        String content = Files.readString(tempDir.resolve("out.jsonl"));
        assertThat(content).doesNotStartWith("PRJXPENC");
        assertThat(content).contains("\"content\":\"hello world content\"");
    }

    @Test
    void execute_withEnvPassword_writesEncryptedFile() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_PASSWORD_XYZ");

        uut(cfg, "env-pw").execute();

        byte[] bytes = Files.readAllBytes(tempDir.resolve("out.jsonl"));
        assertThat(new String(bytes, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("PRJXPENC");

        String decrypted = decryptedOutput(cfg, "env-pw".toCharArray());
        assertThat(decrypted).contains("\"content\":\"hello world content\"");
    }

    @Test
    void execute_withForceEncryptAndNoPassword_generatesAndRepeatsBanner() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_NO_PASSWORD_XYZ");
        cfg.getTransfer().setEncrypt(TransferEncryptMode.TRUE);

        uut(cfg, null).execute();

        String console = capturedOut.toString();
        assertThat(console).contains("[SECURITY] Generated transfer password:");
        assertThat(console).contains("[SECURITY] REPEAT transfer password:");

        String generated = console.lines()
                .dropWhile(line -> !line.equals("[SECURITY] Generated transfer password:"))
                .skip(1)
                .findFirst()
                .orElseThrow();
        assertThat(generated).isNotBlank();

        byte[] bytes = Files.readAllBytes(tempDir.resolve("out.jsonl"));
        assertThat(new String(bytes, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("PRJXPENC");
        String decrypted = decryptedOutput(cfg, generated.toCharArray());
        assertThat(decrypted).contains("\"content\":\"hello world content\"");
    }

    @Test
    void execute_withNoEncryptAndEnvPassword_writesPlaintext() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_PASSWORD_XYZ");
        cfg.getTransfer().setEncrypt(TransferEncryptMode.FALSE);

        uut(cfg, "env-pw").execute();

        String content = Files.readString(tempDir.resolve("out.jsonl"));
        assertThat(content).doesNotStartWith("PRJXPENC");
        assertThat(content).contains("\"content\":\"hello world content\"");
    }

    private ChunkerFactory stubFactory() {
        PxChunker stub = new PxChunker() {
            @Override
            public Stream<PxChunk> chunk(File f) {
                return Stream.of(PxChunk.create(c -> {
                    c.setId("chunk-1");
                    c.setMimeType("text/plain");
                    c.setFile(f.getAbsolutePath());
                    c.setContent("hello world content");
                }));
            }

            @Override
            public boolean matches(File f) {
                return true;
            }
        };
        ChunkerFactory factory = Mockito.mock(ChunkerFactory.class);
        Mockito.when(factory.createChunker(Mockito.any(File.class)))
                .thenAnswer(invocation -> Stream.of(stub));
        Mockito.when(factory.listPostWalkChunker()).thenReturn(Stream.empty());
        return factory;
    }

    private VetoRegistry noVeto() {
        VetoRegistry vetos = Mockito.mock(VetoRegistry.class);
        Mockito.when(vetos.shouldVeto(Mockito.any(Path.class))).thenReturn(false);
        return vetos;
    }

    private TransferPasswordResolver resolverWith(String key, String value) {
        AbstractEnvironment env = new AbstractEnvironment() {
        };
        Map<String, Object> props = new HashMap<>();
        if (value != null) {
            props.put(key, value);
        }
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new TransferPasswordResolver(env);
    }
}
