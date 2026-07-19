package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.beans.factory.ObjectProvider;

class LangChain4jChatModelBeanPostProcessor extends AbstractModelBeanPostProcessor {

    LangChain4jChatModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        super(langfuseOtelProvider, ModelFramework.LANGCHAIN4J);
    }
}
