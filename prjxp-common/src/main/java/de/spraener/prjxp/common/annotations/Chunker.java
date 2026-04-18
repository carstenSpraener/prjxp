package de.spraener.prjxp.common.annotations;

import de.spraener.prjxp.common.model.PxFileType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Chunker {
    PxFileType[] fileTypes() default {};
}