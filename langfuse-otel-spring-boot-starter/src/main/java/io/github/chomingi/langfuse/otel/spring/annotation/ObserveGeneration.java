package io.github.chomingi.langfuse.otel.spring.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ObserveGeneration {

    String name() default "";

    String model() default "";

    String system() default "";

    String operation() default "chat";
}
