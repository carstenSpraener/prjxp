package de.spraener.prjxp.common.annotations;

import de.spraener.prjxp.common.model.PxFileType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Retriever {
    PxFileType[] fileTypes() default {};

    int priority() default 10;

    String metaDataKey() default "";

    String metaDataValue() default "";
}
