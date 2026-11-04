package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.PrjXPCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"de.spraener.prjxp"})
public class McpServer {

    public static void main(String[] args) {
        PrjXPCli.readDotEnv(args);
        SpringApplication.run(McpServer.class, args);
    }

}
