package io.github.chomingi.langfuse.otel.spring.annotation;

import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

@Aspect
public class ObserveGenerationAspect {

    private static final Logger log = LoggerFactory.getLogger(ObserveGenerationAspect.class);

    private final LangfuseOtel langfuseOtel;

    public ObserveGenerationAspect(LangfuseOtel langfuseOtel) {
        this.langfuseOtel = langfuseOtel;
    }

    @Around("@annotation(io.github.chomingi.langfuse.otel.spring.annotation.ObserveGeneration)")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        LangfuseGeneration generation = null;
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            ObserveGeneration annotation = method.getAnnotation(ObserveGeneration.class);

            String name = annotation.name().isEmpty() ? method.getName() : annotation.name();

            generation = new LangfuseGeneration(langfuseOtel.getTracer(), name);

            if (!annotation.operation().isEmpty()) {
                generation.operationName(annotation.operation());
            }
            if (!annotation.model().isEmpty()) {
                generation.model(annotation.model());
            }
            if (!annotation.system().isEmpty()) {
                generation.system(annotation.system());
            }
        } catch (Exception e) {
            log.debug("Langfuse @ObserveGeneration setup failed, proceeding without tracing", e);
        }

        try {
            Object result = joinPoint.proceed();

            if (generation != null && result != null) {
                try { generation.output(result); } catch (Exception ignored) {}
            }

            return result;
        } catch (Throwable t) {
            if (generation != null) {
                try { generation.recordException(t); } catch (Exception ignored) {}
            }
            throw t;
        } finally {
            if (generation != null) {
                try { generation.end(); } catch (Exception ignored) {}
            }
        }
    }
}
