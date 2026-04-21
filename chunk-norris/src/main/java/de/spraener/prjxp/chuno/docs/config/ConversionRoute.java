package de.spraener.prjxp.chuno.docs.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "prjxp.conversion.routes")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversionRoute {
    private String id;
    private String from;
    private String to;
    private List<String> converterPath;
}
