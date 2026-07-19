package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.beans.factory.ObjectProvider;

class SpringAiChatModelBeanPostProcessor extends AbstractModelBeanPostProcessor {

    SpringAiChatModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        super(langfuseOtelProvider, ModelFramework.SPRING_AI);
    }
}
