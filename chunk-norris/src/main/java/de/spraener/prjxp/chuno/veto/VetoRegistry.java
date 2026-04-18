package de.spraener.prjxp.chuno.veto;

import de.spraener.prjxp.common.annotations.ChunkVeto;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VetoRegistry {
    private final List<VetoMethodWrapper> vetoMethods = new ArrayList<>();
    private final ListableBeanFactory beanFactory;

    private void fillVetoMethods() {
        // Alle Beans finden, die Methoden mit @ChunkVeto haben könnten
        String[] beanNames = beanFactory.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            Object bean = beanFactory.getBean(beanName);
            Class<?> targetClass = bean.getClass();

            // Reflection-Utility von Spring nutzen, um Methoden zu finden
            ReflectionUtils.doWithMethods(targetClass, method -> {
                if (method.isAnnotationPresent(ChunkVeto.class)) {
                    // Validierung der Signatur: muss boolean liefern und Path als Argument haben
                    if (method.getReturnType().equals(boolean.class) && method.getParameterCount() == 1) {
                        vetoMethods.add(new VetoMethodWrapper(bean, method));
                    }
                }
            });
        }
    }

    public boolean shouldVeto(Path path) {
        if (vetoMethods.isEmpty()) {
            fillVetoMethods();
        }
        return vetoMethods.stream().anyMatch(m -> m.check(path));
    }

    // Hilfsklasse zum Aufruf
    @RequiredArgsConstructor
    @Log
    private static class VetoMethodWrapper {
        private final Object bean;
        private final Method method;

        public boolean check(Path path) {
            try {
                boolean veto = (boolean) method.invoke(bean, path);
                if (veto) {
                    log.info("File '%s' got a veto from '%s'".formatted(path.toAbsolutePath(), bean.getClass().getSimpleName() + "." + method.getName()));
                }
                return veto;
            } catch (Exception e) {
                return false; // Im Zweifel kein Veto oder Fehler-Logging
            }
        }
    }
}
