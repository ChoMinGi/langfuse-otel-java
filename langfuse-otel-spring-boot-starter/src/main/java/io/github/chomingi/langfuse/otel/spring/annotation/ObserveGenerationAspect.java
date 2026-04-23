package io.github.chomingi.langfuse.otel.spring.annotation;

import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
public class ObserveGenerationAspect {

    private final LangfuseOtel langfuseOtel;

    public ObserveGenerationAspect(LangfuseOtel langfuseOtel) {
        this.langfuseOtel = langfuseOtel;
    }

    @Around("@annotation(io.github.chomingi.langfuse.otel.spring.annotation.ObserveGeneration)")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        ObserveGeneration annotation = method.getAnnotation(ObserveGeneration.class);

        String name = annotation.name().isEmpty() ? method.getName() : annotation.name();

        try (LangfuseGeneration generation = new LangfuseGeneration(
                langfuseOtel.getTracer(), name)) {

            if (!annotation.model().isEmpty()) {
                generation.model(annotation.model());
            }
            if (!annotation.system().isEmpty()) {
                generation.system(annotation.system());
            }

            Object result = joinPoint.proceed();

            if (result != null) {
                generation.output(result);
            }

            return result;
        }
    }
}
