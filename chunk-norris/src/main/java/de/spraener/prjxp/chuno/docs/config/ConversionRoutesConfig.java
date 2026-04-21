package de.spraener.prjxp.chuno.docs.config;

import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import de.spraener.prjxp.common.util.SpringContextSupplier;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Data
@Log
public class ConversionRoutesConfig {
    private List<ConversionRoute> predefinedRoutes = new ArrayList<>();
    private final SpringContextSupplier contextSupplier;

    public ConversionRoutesConfig(SpringContextSupplier contextSupplier ) {
        this.contextSupplier = contextSupplier;
    }

    public List<DocConversionAgent<?, ?>> findPredefinedRouteAgents(DocArtifaktType start, DocArtifaktType end) {
        ApplicationContext applicationContext = contextSupplier.getContext();
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext wurde nicht gesetzt.");
        }

        for (ConversionRoute route : predefinedRoutes) {
            if (Objects.equals(route.getFrom(), start.name()) && Objects.equals(route.getTo(), end.name())) {
                log.info("Found predefined route '%s' from %s to %s.".formatted(route.getId(), start.name(), end.name()));
                return route.getConverterPath().stream()
                        .map(beanName -> {
                            try {
                                Object bean = applicationContext.getBean(beanName);
                                if (bean instanceof DocConversionAgent) {
                                    return (DocConversionAgent<?, ?>) bean;
                                } else {
                                    throw new IllegalStateException("Bean '%s' in converterPath for route '%s' is not a DocConversionAgent.".formatted(beanName, route.getId()));
                                }
                            } catch (BeansException e) {
                                throw new IllegalStateException("Bean '%s' in converterPath for route '%s' not found: %s".formatted(beanName, route.getId(), e.getMessage()), e);
                            }
                        })
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }
}
