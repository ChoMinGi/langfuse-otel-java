package io.github.chomingi.langfuse.otel.spring;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.util.ClassUtils;

import java.util.Locale;

/** Resolves stable model span names without leaking Spring proxy class names. */
final class ModelSpanNameSupport {

    private ModelSpanNameSupport() {
    }

    static String resolve(Object delegate, String operation, String... typeSuffixes) {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(delegate);
        if (targetClass == null) {
            targetClass = ClassUtils.getUserClass(delegate);
        }

        String typeName = targetClass.getSimpleName();
        for (String suffix : typeSuffixes) {
            if (typeName.endsWith(suffix)) {
                typeName = typeName.substring(0, typeName.length() - suffix.length());
                break;
            }
        }
        return typeName.toLowerCase(Locale.ROOT) + "." + operation;
    }
}
