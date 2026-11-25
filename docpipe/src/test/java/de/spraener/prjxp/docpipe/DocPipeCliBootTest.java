package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.common.PrjXPCli;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import java.util.Properties;

import static org.mockito.Mockito.mockStatic;

class DocPipeCliBootTest {
    @Test
    void mainUsesSharedBootConfigurationBeforeStartingSpring() {
        Properties original = new Properties();
        original.putAll(System.getProperties());
        String[] args = {"--prjxp.active-project=test"};
        try (var cli = mockStatic(PrjXPCli.class);
             var spring = mockStatic(SpringApplication.class)) {
            DocPipeCliApp.main(args);
            cli.verify(() -> PrjXPCli.readDotEnv(args));
            spring.verify(() -> SpringApplication.run(DocPipeCliApp.class, args));
        } finally {
            System.setProperties(original);
        }
    }
}
