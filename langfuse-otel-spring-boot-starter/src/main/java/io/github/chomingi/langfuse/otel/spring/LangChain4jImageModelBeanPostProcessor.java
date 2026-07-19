package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.LangfuseOtel;
import org.springframework.beans.factory.ObjectProvider;

class LangChain4jImageModelBeanPostProcessor extends AbstractModelBeanPostProcessor {

    LangChain4jImageModelBeanPostProcessor(ObjectProvider<LangfuseOtel> langfuseOtelProvider) {
        super(langfuseOtelProvider, ModelFramework.LANGCHAIN4J);
    }
}
