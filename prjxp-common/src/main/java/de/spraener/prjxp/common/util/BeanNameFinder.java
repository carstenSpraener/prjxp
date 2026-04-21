package de.spraener.prjxp.common.util;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BeanNameFinder {
    private final SpringContextSupplier contextSupplier;

    public String findBeanName(Object beanInstance) {
        ApplicationContext applicationContext = contextSupplier.getContext();
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext not set.");
        }

        String[] beanNames = applicationContext.getBeanNamesForType(beanInstance.getClass());

        for (String beanName : beanNames) {
            Object registeredBean = applicationContext.getBean(beanName);
            if (registeredBean == beanInstance) {
                return beanName;
            }
        }
        return null;
    }
}
