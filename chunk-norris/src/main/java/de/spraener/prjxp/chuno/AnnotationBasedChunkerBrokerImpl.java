package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.annotations.ChunkNorrisComponent;
import de.spraener.prjxp.common.annotations.Chunker;
import de.spraener.prjxp.common.annotations.PostWalkChunker;
import de.spraener.prjxp.common.model.PxChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
public abstract class AnnotationBasedChunkerBrokerImpl implements ChunkerBroker, ApplicationContextAware {
    private final String rootPkg;
    private List<PxChunker> chunkers;
    private List<PxChunker> postWalkChunkers;
    private ApplicationContext applicationContext;

    protected AnnotationBasedChunkerBrokerImpl(String rootPkg) {
        this.rootPkg = rootPkg;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Stream<PxChunker> findPxChunkers(File f) {
        if (chunkers == null) {
            initChunkers();
        }
        return chunkers.stream().filter(chunker -> chunker.matches(f));
    }

    @Override
    public Stream<PxChunker> listPostWalkChunker() {
        if (postWalkChunkers == null) {
            initPostWalkChunkers();
        }
        return postWalkChunkers.stream();
    }

    private void initPostWalkChunkers() {
        this.postWalkChunkers = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ChunkNorrisComponent.class));

        for (BeanDefinition bd : scanner.findCandidateComponents(rootPkg)) {
            try {
                Class<?> clazz = ClassUtils.forName(bd.getBeanClassName(), ClassUtils.getDefaultClassLoader());

                // Check methods
                for (Method method : clazz.getDeclaredMethods()) {
                    if (isPostWalkChunkerMethod(method)) {
                        this.postWalkChunkers.add(createPostWalkChunkerByMethod(clazz, method));
                    }
                }
            } catch (ClassNotFoundException e) {
                log.error("Could not load class: {}", bd.getBeanClassName(), e);
            }
        }
    }

    private boolean isPostWalkChunkerMethod(Method method) {
        if (!method.isAnnotationPresent(PostWalkChunker.class)) {
            return false;
        }
        if (method.getParameterCount() != 0) {
            return false;
        }
        if (!Stream.class.isAssignableFrom(method.getReturnType())) {
            return false;
        }
        return true;
    }

    private void initChunkers() {
        this.chunkers = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ChunkNorrisComponent.class));

        for (BeanDefinition bd : scanner.findCandidateComponents(rootPkg)) {
            try {
                Class<?> clazz = ClassUtils.forName(bd.getBeanClassName(), ClassUtils.getDefaultClassLoader());

                // Check methods
                for (Method method : clazz.getDeclaredMethods()) {
                    if (isChunkerMethod(method)) {
                        this.chunkers.add(createByMethod(clazz, method));
                    }
                }

                // Check if class itself is annotated and not already handled by methods (or in addition)
                if (clazz.isAnnotationPresent(Chunker.class) && PxChunker.class.isAssignableFrom(clazz)) {
                    this.chunkers.add(createByClazz(clazz));
                }

            } catch (ClassNotFoundException e) {
                log.error("Could not load class: {}", bd.getBeanClassName(), e);
            }
        }
    }

    private boolean isChunkerMethod(Method method) {
        if (!method.isAnnotationPresent(Chunker.class)) {
            return false;
        }
        if (method.getParameterCount() != 1 || !File.class.equals(method.getParameterTypes()[0])) {
            return false;
        }
        if (!Stream.class.isAssignableFrom(method.getReturnType())) {
            return false;
        }
        // Additional check for generic type List<PxChunk> is tricky at runtime due to erasure,
        // but we can check if it's a List.
        return true;
    }

    private PxChunker createByMethod(Class<?> clazz, Method method) {
        try {
            Object instance = applicationContext.getBean(clazz);
            return new PxChunkerMethodWrapper(instance, method);
        } catch (Exception e) {
            log.error("FATAL: Annotated method {} in class {} can not be invoked as PxChunker: {}", method.getName(), clazz.getName(), e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private PxChunker createPostWalkChunkerByMethod(Class<?> clazz, Method method) {
        try {
            Object instance = applicationContext.getBean(clazz);
            return new PxPostWalkChunkerMethodWrapper(instance, method);
        } catch (Exception e) {
            log.error("FATAL: Annotated method {} in class {} can not be invoked as PostWalkChunker: {}", method.getName(), clazz.getName(), e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private PxChunker createByClazz(Class<?> clazz) {
        try {
            try {
                Object bean = applicationContext.getBean(clazz);
                if (!(bean instanceof PxChunker)) {
                    throw new ClassCastException(String.format("Bean %s is not a PxChunker", clazz.getName()));
                }
                return (PxChunker) bean;
            } catch (NoSuchBeanDefinitionException nsbdXC) {
                return (PxChunker) clazz.getDeclaredConstructor().newInstance();
            }
        } catch (Exception e) {
            log.error("FATAL: Annotated class {} can not be constructed as PxChunker: {}", clazz.getName(), e.getMessage());
            throw new RuntimeException(e);
        }
    }

    static class PxChunkerMethodWrapper implements PxChunker {
        private Object instance;
        private Method method;
        private Chunker annotation;

        public PxChunkerMethodWrapper(Object instance, Method method) {
            this.instance = instance;
            this.method = method;
            checkMethodValidity(method);
            this.annotation = method.getAnnotation(Chunker.class);
        }

        private void checkMethodValidity(Method method) {
            if (!method.isAnnotationPresent(Chunker.class)) {
                throw new IllegalArgumentException("Method is not annotated with @Chunker");
            }
            if (method.getParameterCount() != 1 || !File.class.equals(method.getParameterTypes()[0])) {
                throw new IllegalArgumentException("Method has invalid signature");
            }
            if (!Stream.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalArgumentException("Method has invalid return type (not Stream<PxChunk>)");
            }
        }

        @Override
        public Stream<PxChunk> chunk(File f) {
            try {
                return (Stream<PxChunk>) method.invoke(instance, f);
            } catch (IllegalAccessException | InvocationTargetException itXC) {
                throw new RuntimeException(itXC.getCause());
            }
        }

        @Override
        public boolean matches(File f) {
            for (var ft : this.annotation.fileTypes()) {
                if (ft.matches(f)) {
                    return true;
                }
            }
            return false;
        }
    }

    static class PxPostWalkChunkerMethodWrapper implements PxChunker {
        private Object instance;
        private Method method;
        private Chunker annotation;

        public PxPostWalkChunkerMethodWrapper(Object instance, Method method) {
            this.instance = instance;
            this.method = method;
            checkMethodValidity(method);
            this.annotation = method.getAnnotation(Chunker.class);
        }

        private void checkMethodValidity(Method method) {
            if (!method.isAnnotationPresent(PostWalkChunker.class)) {
                throw new IllegalArgumentException("Method is not annotated with @PostWalkChunker");
            }
            if (method.getParameterCount() != 0) {
                throw new IllegalArgumentException("Method has invalid signature");
            }
            if (!Stream.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalArgumentException("Method has invalid return type (not Stream<PxChunk>)");
            }
        }

        @Override
        public Stream<PxChunk> chunk(File f) {
            try {
                return (Stream<PxChunk>) method.invoke(instance);
            } catch (IllegalAccessException | InvocationTargetException itXC) {
                throw new RuntimeException(itXC.getCause());
            }
        }

        @Override
        public boolean matches(File f) {
            return false;
        }
    }
}
