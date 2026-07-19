package io.github.chomingi.langfuse.otel.spring;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.spring.annotation.ObserveGeneration;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.aop.support.AopUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelBeanPostProcessorSupportTest {

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LangfuseOtelAutoConfiguration.class,
                    SpringAiAutoConfiguration.class,
                    LangChain4jAutoConfiguration.class))
            .withBean(OpenTelemetry.class, OpenTelemetry::noop);

    @Test
    void applicationContextSupportsConcreteSpringAiLookupAfterAutomaticInstrumentation() {
        contextRunner.withBean("providerChatModel", ProviderSpringAiChatModel.class,
                        ProviderSpringAiChatModel::new)
                .run(context -> {
                    ProviderSpringAiChatModel concrete = context.getBean(
                            "providerChatModel", ProviderSpringAiChatModel.class);

                    assertThat(concrete.providerOperation()).isEqualTo("provider-extension");
                    concrete.call(new Prompt("context"));
                });
    }

    @Test
    void explicitObservationOnAModelBeanTakesPrecedenceWithoutCreatingADuplicateSpan() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class,
                        LangfuseOtelAutoConfiguration.class,
                        SpringAiAutoConfiguration.class))
                .withUserConfiguration(ObservedModelConfiguration.class)
                .withBean(OpenTelemetry.class, otel::getOpenTelemetry,
                        beanDefinition -> beanDefinition.setDestroyMethodName(""))
                .withPropertyValues("spring.aop.proxy-target-class=true")
                .run(context -> {
                    ObservedSpringAiChatModel model = context.getBean(
                            ObservedSpringAiChatModel.class);

                    org.springframework.ai.chat.model.ChatResponse direct =
                            model.call(new Prompt("direct"));

                    assertThat(direct.getResult().getOutput().getText()).isEqualTo("observed");
                    assertThat(model.getInvocations()).isEqualTo(1);
                    assertThat(otel.getSpans()).extracting(span -> span.getName())
                            .containsExactly("explicit-model");
                });
    }

    @Test
    void applicationContextDoesNotExposeStreamingOnlyLangChain4jBeanAsChatModel() {
        contextRunner.withBean("streamingOnly", StreamingOnlyLangChain4jModel.class,
                        StreamingOnlyLangChain4jModel::new)
                .run(context -> {
                    assertThat(context.getBean("streamingOnly", StreamingOnlyLangChain4jModel.class))
                            .isInstanceOf(StreamingChatModel.class)
                            .isNotInstanceOf(ChatModel.class);
                    assertThat(context.getBeansOfType(ChatModel.class)).doesNotContainKey("streamingOnly");
                });
    }

    @Test
    void springAiProxyPreservesConcreteAndExtensionTypesAndInstrumentsTheModelOnce() {
        ProviderSpringAiChatModel target = new ProviderSpringAiChatModel();
        LangfuseOtel langfuseOtel = LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build();

        Object proxied = ModelBeanPostProcessorSupport.instrumentSpringAi(target, langfuseOtel);

        assertThat(proxied).isInstanceOf(ProviderSpringAiChatModel.class);
        assertThat(proxied).isInstanceOf(ProviderExtension.class);
        assertThat(((ProviderExtension) proxied).providerOperation()).isEqualTo("provider-extension");

        ((org.springframework.ai.chat.model.ChatModel) proxied).call(new Prompt("chat"));

        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans()).extracting(span -> span.getName())
                .containsExactly("providerspringai.chat");
    }

    @Test
    void repeatedSpringAiProcessingIsIdempotent() {
        LangfuseOtel langfuseOtel = LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build();
        Object first = ModelBeanPostProcessorSupport.instrumentSpringAi(
                new ProviderSpringAiChatModel(), langfuseOtel);

        Object second = ModelBeanPostProcessorSupport.instrumentSpringAi(first, langfuseOtel);

        assertThat(second).isSameAs(first);
        ((org.springframework.ai.chat.model.ChatModel) second).call(new Prompt("once"));
        assertThat(otel.getSpans()).hasSize(1);
    }

    @Test
    void staticNestedProxyWithExistingAdviceIsNotInstrumentedAgain() {
        LangfuseOtel langfuseOtel = LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build();
        Object instrumented = ModelBeanPostProcessorSupport.instrumentSpringAi(
                new ProviderSpringAiChatModel(), langfuseOtel);

        ProxyFactory outerFactory = new ProxyFactory();
        outerFactory.setTarget(instrumented);
        outerFactory.setProxyTargetClass(true);
        outerFactory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        Object outer = outerFactory.getProxy();

        Object processedAgain = ModelBeanPostProcessorSupport.instrumentSpringAi(outer, langfuseOtel);

        assertThat(processedAgain).isSameAs(outer);
        ((org.springframework.ai.chat.model.ChatModel) processedAgain).call(new Prompt("once"));
        assertThat(otel.getSpans()).hasSize(1);
    }

    @Test
    void adviceInspectionDoesNotResolveDynamicTargetSource() {
        CountingDynamicTargetSource targetSource = new CountingDynamicTargetSource(
                new ProviderSpringAiChatModel());
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTargetSource(targetSource);
        proxyFactory.setInterfaces(
                org.springframework.ai.chat.model.ChatModel.class, ProviderExtension.class);
        Object dynamicProxy = proxyFactory.getProxy();

        Object processed = ModelBeanPostProcessorSupport.instrumentSpringAi(
                dynamicProxy, LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        assertThat(processed).isNotNull();
        assertThat(targetSource.getTargetInvocations()).isZero();
    }

    @Test
    void existingJdkProxyKeepsInterfacesExtensionCallsAndStableSpanName() {
        ProxyFactory existingFactory = new ProxyFactory();
        existingFactory.setTarget(new ProviderSpringAiChatModel());
        existingFactory.setInterfaces(
                org.springframework.ai.chat.model.ChatModel.class, ProviderExtension.class);
        Object existingProxy = existingFactory.getProxy();
        assertThat(AopUtils.isJdkDynamicProxy(existingProxy)).isTrue();

        Object processed = ModelBeanPostProcessorSupport.instrumentSpringAi(
                existingProxy, LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        assertThat(AopUtils.isJdkDynamicProxy(processed)).isTrue();
        assertThat(processed).isInstanceOf(org.springframework.ai.chat.model.ChatModel.class);
        assertThat(processed).isInstanceOf(ProviderExtension.class);
        assertThat(((ProviderExtension) processed).providerOperation()).isEqualTo("provider-extension");
        ((org.springframework.ai.chat.model.ChatModel) processed).call(new Prompt("jdk"));

        assertThat(otel.getSpans()).hasSize(1);
        assertThat(otel.getSpans().get(0).getName()).isEqualTo("providerspringai.chat");
    }

    @Test
    void finalConcreteModelIsPreservedInsteadOfBeingReplacedByAnInterfaceDecorator() {
        FinalSpringAiChatModel target = new FinalSpringAiChatModel();

        Object processed = ModelBeanPostProcessorSupport.instrumentSpringAi(
                target, LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        assertThat(processed).isSameAs(target);
        assertThat(processed).isInstanceOf(FinalSpringAiChatModel.class);
    }

    @Test
    void finalProviderExtensionMethodMakesTheWholeConcreteModelFailOpen() {
        FinalExtensionSpringAiChatModel target = new FinalExtensionSpringAiChatModel();

        Object processed = ModelBeanPostProcessorSupport.instrumentSpringAi(
                target, LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        assertThat(processed).isSameAs(target);
        assertThat(((ProviderExtension) processed).providerOperation()).isEqualTo("provider-state");
        ((org.springframework.ai.chat.model.ChatModel) processed).call(new Prompt("not-instrumented"));
        assertThat(otel.getSpans()).isEmpty();
    }

    @Test
    void scopedProxyIsSkippedWhileItsTargetProducesOneSpan() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        LangfuseOtelAutoConfiguration.class,
                        SpringAiAutoConfiguration.class,
                        LangChain4jAutoConfiguration.class))
                .withUserConfiguration(ScopedModelConfiguration.class)
                .withBean(OpenTelemetry.class, otel::getOpenTelemetry,
                        beanDefinition -> beanDefinition.setDestroyMethodName(""))
                .run(context -> {
                    Object scopedBean = context.getBean("scopedProviderChatModel");
                    assertThat(scopedBean).isInstanceOf(ScopedObject.class);

                    ((org.springframework.ai.chat.model.ChatModel) scopedBean)
                            .call(new Prompt("scoped"));

                    assertThat(otel.getSpans()).hasSize(1);
                });
    }

    @Test
    void allowedSetterCycleUsesTheSameEarlyProxyAsTheFinalBeanAndInstrumentsOnce() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        LangfuseOtelAutoConfiguration.class,
                        SpringAiAutoConfiguration.class,
                        LangChain4jAutoConfiguration.class))
                .withUserConfiguration(CircularModelConfiguration.class)
                .withInitializer(context -> ((DefaultListableBeanFactory) context.getBeanFactory())
                        .setAllowCircularReferences(true))
                .withBean(OpenTelemetry.class, otel::getOpenTelemetry,
                        beanDefinition -> beanDefinition.setDestroyMethodName(""))
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    CircularSpringAiChatModel model = context.getBean(
                            CircularSpringAiChatModel.class);
                    CircularModelCollaborator collaborator = context.getBean(
                            CircularModelCollaborator.class);

                    assertThat(AopUtils.isAopProxy(model)).isTrue();
                    assertThat(model).isSameAs(collaborator.getModel());
                    assertThat(model.getCollaborator()).isSameAs(collaborator);

                    model.call(new Prompt("cycle"));

                    assertThat(otel.getSpans()).hasSize(1);
                    assertThat(otel.getSpans().get(0).getName())
                            .isEqualTo("circularspringai.chat");
                });
    }

    @Test
    void streamingOnlyLangChain4jModelNeverBecomesASynchronousCandidateAndCompletesOnce() {
        StreamingOnlyLangChain4jModel target = new StreamingOnlyLangChain4jModel();

        Object processed = ModelBeanPostProcessorSupport.instrumentLangChain4j(
                target, LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        assertThat(processed).isInstanceOf(StreamingOnlyLangChain4jModel.class);
        assertThat(processed).isInstanceOf(StreamingChatModel.class);
        assertThat(processed).isNotInstanceOf(ChatModel.class);
        assertThat(((ProviderExtension) processed).providerOperation()).isEqualTo("provider-extension");

        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        ((StreamingChatModel) processed).chat(chatRequest("streaming-only"),
                terminalHandler(completed));

        assertThat(completed.get()).isNotNull();
        assertThat(completed.get().aiMessage().text()).isEqualTo("stream-complete");
        assertThat(otel.getSpans()).hasSize(1);
    }

    @Test
    void dualLangChain4jModelKeepsBothCapabilitiesAndCompletesEachOperationOnce() {
        DualLangChain4jModel target = new DualLangChain4jModel();

        Object processed = ModelBeanPostProcessorSupport.instrumentLangChain4j(
                target, LangfuseOtel.externalBuilder(otel.getOpenTelemetry()).build());

        assertThat(processed).isInstanceOf(DualLangChain4jModel.class);
        assertThat(processed).isInstanceOf(StreamingChatModel.class);
        assertThat(processed).isInstanceOf(ChatModel.class);

        ChatResponse synchronous = ((ChatModel) processed).chat(chatRequest("sync"));
        AtomicReference<ChatResponse> streamed = new AtomicReference<>();
        ((StreamingChatModel) processed).chat(chatRequest("stream"), terminalHandler(streamed));

        assertThat(synchronous.aiMessage().text()).isEqualTo("sync-complete");
        assertThat(streamed.get()).isNotNull();
        assertThat(streamed.get().aiMessage().text()).isEqualTo("stream-complete");
        assertThat(otel.getSpans()).hasSize(2);
    }

    private static ChatRequest chatRequest(String text) {
        return ChatRequest.builder().messages(UserMessage.from(text)).build();
    }

    private static StreamingChatResponseHandler terminalHandler(
            AtomicReference<ChatResponse> completed) {
        AtomicBoolean terminal = new AtomicBoolean();
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (!terminal.compareAndSet(false, true)) {
                    throw new AssertionError("duplicate streaming terminal callback");
                }
                completed.set(response);
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError("unexpected streaming failure", error);
            }
        };
    }

    public interface ProviderExtension {
        String providerOperation();
    }

    public static class ProviderSpringAiChatModel
            implements org.springframework.ai.chat.model.ChatModel, ProviderExtension {

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public String providerOperation() {
            return "provider-extension";
        }
    }

    public static final class FinalSpringAiChatModel implements org.springframework.ai.chat.model.ChatModel {
        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage("ok"))));
        }
    }

    public static class FinalExtensionSpringAiChatModel extends ProviderSpringAiChatModel {

        private final String providerState = "provider-state";

        @Override
        public final String providerOperation() {
            return providerState;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ScopedModelConfiguration {

        @Bean
        @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE,
                proxyMode = ScopedProxyMode.TARGET_CLASS)
        ProviderSpringAiChatModel scopedProviderChatModel() {
            return new ProviderSpringAiChatModel();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ObservedModelConfiguration {

        @Bean
        ObservedSpringAiChatModel observedSpringAiChatModel() {
            return new ObservedSpringAiChatModel();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CircularModelConfiguration {

        @Bean
        CircularSpringAiChatModel circularSpringAiChatModel() {
            return new CircularSpringAiChatModel();
        }

        @Bean
        CircularModelCollaborator circularModelCollaborator() {
            return new CircularModelCollaborator();
        }
    }

    public static class CircularSpringAiChatModel
            implements org.springframework.ai.chat.model.ChatModel {

        private CircularModelCollaborator collaborator;

        @Autowired
        void setCollaborator(CircularModelCollaborator collaborator) {
            this.collaborator = collaborator;
        }

        CircularModelCollaborator getCollaborator() {
            return collaborator;
        }

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage("cycle-ok"))));
        }
    }

    static class CircularModelCollaborator {

        private CircularSpringAiChatModel model;

        @Autowired
        void setModel(CircularSpringAiChatModel model) {
            this.model = model;
        }

        CircularSpringAiChatModel getModel() {
            return model;
        }
    }

    public static class ObservedSpringAiChatModel
            implements org.springframework.ai.chat.model.ChatModel {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        @ObserveGeneration(name = "explicit-model")
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            invocations.incrementAndGet();
            return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(new AssistantMessage("observed"))));
        }

        int getInvocations() {
            return invocations.get();
        }
    }

    static final class CountingDynamicTargetSource implements TargetSource {

        private final Object target;
        private final AtomicInteger targetInvocations = new AtomicInteger();

        CountingDynamicTargetSource(Object target) {
            this.target = target;
        }

        @Override
        public Class<?> getTargetClass() {
            return target.getClass();
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public Object getTarget() {
            targetInvocations.incrementAndGet();
            return target;
        }

        @Override
        public void releaseTarget(Object target) {
        }

        int getTargetInvocations() {
            return targetInvocations.get();
        }
    }

    public static class StreamingOnlyLangChain4jModel implements StreamingChatModel, ProviderExtension {
        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("stream-partial");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("stream-complete"))
                    .build());
        }

        @Override
        public String providerOperation() {
            return "provider-extension";
        }
    }

    public static class DualLangChain4jModel extends StreamingOnlyLangChain4jModel implements ChatModel {
        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("sync-complete"))
                    .build();
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return ChatRequestParameters.builder().build();
        }

        @Override
        public List<ChatModelListener> listeners() {
            return List.of();
        }

        @Override
        public ModelProvider provider() {
            return null;
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return Set.of();
        }
    }
}
