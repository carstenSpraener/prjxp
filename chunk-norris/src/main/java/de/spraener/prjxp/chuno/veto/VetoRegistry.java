package de.spraener.prjxp.chuno.veto;

import de.spraener.prjxp.common.annotations.ChunkVeto;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VetoRegistry implements BeanPostProcessor {
    private final List<VetoMethodWrapper> vetoMethods = new ArrayList<>();
    private final ListableBeanFactory beanFactory;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = bean.getClass();

        // MethodIntrospector findet alle Methoden, auf die das Kriterium zutrifft
        Map<Method, ChunkVeto> annotatedMethods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<ChunkVeto>) method ->
                        // Sucht nach der Annotation (unterstützt auch Meta-Annotationen)
                        AnnotatedElementUtils.findMergedAnnotation(method, ChunkVeto.class)
        );
        if (!annotatedMethods.isEmpty()) {
            annotatedMethods.forEach((method, annotation) -> {
                if (method.getReturnType().equals(boolean.class) && method.getParameterCount() == 1) {
                    vetoMethods.add(new VetoMethodWrapper(bean, method));
                }
            });
        }
        return bean;
    }

    public boolean shouldVeto(Path path) {
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
                    log.fine("File '%s' got a veto from '%s'".formatted(path.toAbsolutePath(), bean.getClass().getSimpleName() + "." + method.getName()));
                }
                return veto;
            } catch (Exception e) {
                return false; // Im Zweifel kein Veto oder Fehler-Logging
            }
        }
    }
}
