package de.spraener.prjxp.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"de.spraener.prjxp"})
public class McpServer {

    public static void main(String[] args) {
        SpringApplication.run(McpServer.class, args);
    }

}
