package io.github.chomingi.langfuse.otel.spring;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.github.chomingi.langfuse.otel.JsonUtils;
import io.github.chomingi.langfuse.otel.LangfuseAttributes;
import io.github.chomingi.langfuse.otel.LangfuseContext;
import io.github.chomingi.langfuse.otel.LangfuseGeneration;
import io.github.chomingi.langfuse.otel.LangfuseOtel;
import io.github.chomingi.langfuse.otel.LangfuseTraceContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * LangChain4j streaming chat decorator that records one Langfuse span per request.
 */
public class TracingStreamingLangChain4jChatModel implements StreamingChatModel, ChatModel {

    private static final Logger log = LoggerFactory.getLogger(TracingStreamingLangChain4jChatModel.class);

    private final Object delegate;
    private final LangfuseOtel langfuseOtel;

    /**
     * Creates a tracing decorator for a streaming or dual-mode LangChain4j chat model.
     *
     * @param delegate streaming chat model to invoke
     * @param langfuseOtel tracing integration
     */
    public TracingStreamingLangChain4jChatModel(Object delegate, LangfuseOtel langfuseOtel) {
        this.delegate = delegate;
        this.langfuseOtel = langfuseOtel;
    }

    // --- StreamingChatModel ---

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        InvocationState state = startState(chatRequest);
        BoundedTextAccumulator accumulated;
        StreamingChatResponseHandler tracingHandler;
        try {
            accumulated = langfuseOtel.getContentCapturePolicy().isOutputCaptureEnabled()
                    ? new BoundedTextAccumulator(langfuseOtel.getContentCapturePolicy().getMaxLength())
                    : null;
            tracingHandler = tracingHandler(handler, state, accumulated, new AtomicBoolean(true));
        } catch (Throwable failure) {
            abortState(state);
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse streaming callback instrumentation setup failed, proceeding without tracing",
                    failure);
            ((StreamingChatModel) delegate).doChat(chatRequest, handler);
            return;
        }

        try {
            runWithContext(state, () ->
                    ((StreamingChatModel) delegate).doChat(chatRequest, tracingHandler));
        } catch (Throwable t) {
            terminateWithError(state, t);
            throw t;
        }
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        if (delegate instanceof StreamingChatModel) {
            return ((StreamingChatModel) delegate).defaultRequestParameters();
        }
        if (delegate instanceof ChatModel) {
            return ((ChatModel) delegate).defaultRequestParameters();
        }
        return StreamingChatModel.super.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        List<ChatModelListener> delegateListeners;
        if (delegate instanceof StreamingChatModel) {
            delegateListeners = ((StreamingChatModel) delegate).listeners();
        } else if (delegate instanceof ChatModel) {
            delegateListeners = ((ChatModel) delegate).listeners();
        } else {
            delegateListeners = Collections.emptyList();
        }
        List<ChatModelListener> snapshot = delegateListeners == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(delegateListeners));
        return Collections.singletonList(new ContextPropagatingListener(snapshot));
    }

    @Override
    public ModelProvider provider() {
        if (delegate instanceof StreamingChatModel) {
            return ((StreamingChatModel) delegate).provider();
        }
        if (delegate instanceof ChatModel) {
            return ((ChatModel) delegate).provider();
        }
        return null;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        if (delegate instanceof StreamingChatModel) {
            return ((StreamingChatModel) delegate).supportedCapabilities();
        }
        if (delegate instanceof ChatModel) {
            return ((ChatModel) delegate).supportedCapabilities();
        }
        return StreamingChatModel.super.supportedCapabilities();
    }

    // --- ChatModel (sync) ---

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        if (!(delegate instanceof ChatModel)) {
            throw new UnsupportedOperationException(
                    "Synchronous chat is not supported — the delegate only implements StreamingChatModel");
        }

        ChatModel syncDelegate = (ChatModel) delegate;
        LangfuseGeneration gen = null;
        try {
            gen = new LangfuseGeneration(langfuseOtel.getTracer(), resolveSpanName());
            gen.system("langchain4j");
            setRequestAttributes(gen, chatRequest);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(gen);
            gen = null;
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse instrumentation setup failed, proceeding without tracing", failure);
        }

        try {
            ChatResponse response = syncDelegate.doChat(chatRequest);
            try {
                setSyncResponseAttributes(gen, response);
            } catch (Throwable failure) {
                InstrumentationFailureSupport.rethrowIfFatal(failure);
                log.debug("Failed to record response attributes", failure);
            }
            return response;
        } catch (Throwable t) {
            if (gen != null) {
                InstrumentationFailureSupport.recordExceptionQuietly(langfuseOtel, gen, t);
            }
            throw t;
        } finally {
            if (gen != null) {
                InstrumentationFailureSupport.endQuietly(gen);
            }
        }
    }

    private InvocationState startState(ChatRequest chatRequest) {
        Context parent = Context.current();
        LangfuseTraceContext traceContext = null;
        Span span = null;
        try {
            traceContext = LangfuseContext.current();
            span = langfuseOtel.getTracer().spanBuilder(resolveSpanName())
                    .setParent(parent)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(LangfuseAttributes.OBSERVATION_TYPE, "generation")
                    .setAttribute(LangfuseAttributes.GEN_AI_OPERATION_NAME, "chat")
                    .setAttribute(LangfuseAttributes.GEN_AI_SYSTEM, "langchain4j")
                    .startSpan();
            LangfuseContext.applyTo(span, traceContext);
            setRequestAttributesOnSpan(span, chatRequest);

            InvocationState state = new InvocationState(span, traceContext);
            Context invocationContext = storeStateSafely(
                    contextWithTrace(parent.with(span), traceContext), state);
            state.context(invocationContext);
            return state;
        } catch (Throwable failure) {
            InstrumentationFailureSupport.endQuietly(span);
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Langfuse streaming instrumentation setup failed, proceeding without tracing", failure);

            InvocationState state = new InvocationState(null, traceContext);
            state.context(storeStateSafely(contextWithTrace(parent, traceContext), state));
            return state;
        }
    }

    private Context storeStateSafely(Context context, InvocationState state) {
        try {
            return LangChain4jStreamingContext.storeState(context, state);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not attach LangChain4j streaming state to the invocation context", failure);
            return context;
        }
    }

    private Context contextWithTrace(Context context, LangfuseTraceContext traceContext) {
        if (traceContext == null) return context;
        try {
            return LangfuseContext.storeIn(context, traceContext);
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not attach Langfuse metadata to the streaming invocation context", failure);
            return context;
        }
    }

    private StreamingChatResponseHandler tracingHandler(StreamingChatResponseHandler handler,
                                                         InvocationState state,
                                                         BoundedTextAccumulator accumulated,
                                                         AtomicBoolean firstChunk) {
        InvocationHandler invocationHandler = (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(proxy, method, arguments);
            }
            if (state.terminal()) {
                return null;
            }

            synchronized (state.callbackMonitor()) {
                if (state.terminal()) {
                    return null;
                }
                String methodName = method.getName();
                Object[] callbackArguments = arguments == null ? new Object[0] : arguments;
                if ("onCompleteResponse".equals(methodName)) {
                    return onComplete(handler, method, callbackArguments, state, accumulated);
                }
                if ("onError".equals(methodName)) {
                    return onError(handler, method, callbackArguments, state);
                }
                if ("onPartialResponse".equals(methodName)) {
                    recordPartial(state, callbackArguments, accumulated, firstChunk);
                }
                Object[] forwardedArguments = wrapStreamingContext(callbackArguments, state);
                try {
                    return invokeWithContext(state, method, handler, forwardedArguments);
                } catch (Throwable failure) {
                    terminateWithError(state, failure);
                    throw failure;
                }
            }
        };

        return (StreamingChatResponseHandler) Proxy.newProxyInstance(
                StreamingChatResponseHandler.class.getClassLoader(),
                new Class<?>[]{StreamingChatResponseHandler.class},
                invocationHandler);
    }

    private Object onComplete(StreamingChatResponseHandler handler,
                              Method method,
                              Object[] arguments,
                              InvocationState state,
                              BoundedTextAccumulator accumulated) throws Throwable {
        if (!state.markTerminal()) return null;

        try {
            if (state.span() != null) {
                ChatResponse response = arguments.length == 0 ? null : (ChatResponse) arguments[0];
                try {
                    setResponseAttributesOnSpan(state.span(), response, accumulated);
                } catch (Throwable failure) {
                    InstrumentationFailureSupport.rethrowIfFatal(failure);
                    log.debug("Failed to record streaming response attributes", failure);
                }
            }
            return invokeWithContext(state, method, handler, arguments);
        } catch (Throwable failure) {
            recordException(state, failure);
            throw failure;
        } finally {
            endState(state);
        }
    }

    private Object onError(StreamingChatResponseHandler handler,
                           Method method,
                           Object[] arguments,
                           InvocationState state) throws Throwable {
        if (!state.markTerminal()) return null;

        Throwable providerFailure = arguments.length == 0 ? null : (Throwable) arguments[0];
        if (providerFailure != null) recordException(state, providerFailure);
        try {
            return invokeWithContext(state, method, handler, arguments);
        } catch (Throwable callbackFailure) {
            recordException(state, callbackFailure);
            throw callbackFailure;
        } finally {
            endState(state);
        }
    }

    private void recordPartial(InvocationState state,
                               Object[] arguments,
                               BoundedTextAccumulator accumulated,
                               AtomicBoolean firstChunk) {
        try {
            if (state.span() != null && firstChunk.compareAndSet(true, false)) {
                state.span().setAttribute(LangfuseAttributes.OBSERVATION_COMPLETION_START_TIME,
                        java.time.Instant.now().toString());
            }
            if (accumulated != null && arguments.length > 0) {
                String partial = partialText(arguments[0]);
                if (partial != null) accumulated.append(partial);
            }
        } catch (Throwable failure) {
            if (InstrumentationFailureSupport.isFatal(failure)) {
                terminateWithError(state, failure);
            }
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Failed to record a streaming response chunk", failure);
        }
    }

    private String partialText(Object partial) throws ReflectiveOperationException {
        if (partial instanceof String) return (String) partial;
        if (partial == null) return null;
        Method textMethod = partial.getClass().getMethod("text");
        Object text = textMethod.invoke(partial);
        return text == null ? null : String.valueOf(text);
    }

    private Object[] wrapStreamingContext(Object[] arguments, InvocationState state) {
        if (arguments.length < 2 || arguments[arguments.length - 1] == null) return arguments;

        Object callbackContext = arguments[arguments.length - 1];
        try {
            Method streamingHandleMethod = callbackContext.getClass().getMethod("streamingHandle");
            Object streamingHandle = streamingHandleMethod.invoke(callbackContext);
            if (streamingHandle == null) return arguments;

            Class<?> handleType = streamingHandleMethod.getReturnType();
            Object tracingHandle = Proxy.newProxyInstance(
                    handleType.getClassLoader(),
                    new Class<?>[]{handleType},
                    (proxy, method, handleArguments) -> invokeStreamingHandle(
                            proxy, method, handleArguments, streamingHandle, state));
            Object tracingContext = callbackContext.getClass()
                    .getConstructor(handleType)
                    .newInstance(tracingHandle);

            Object[] forwarded = arguments.clone();
            forwarded[forwarded.length - 1] = tracingContext;
            return forwarded;
        } catch (NoSuchMethodException ignored) {
            return arguments;
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not wrap the LangChain4j streaming cancellation handle", failure);
            return arguments;
        }
    }

    private Object invokeStreamingHandle(Object proxy,
                                         Method method,
                                         Object[] arguments,
                                         Object delegateHandle,
                                         InvocationState state) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, method, arguments);
        }
        if ("isCancelled".equals(method.getName()) && state.cancelled()) {
            return true;
        }
        if (!"cancel".equals(method.getName())) {
            return invokeReflectively(method, delegateHandle, arguments);
        }
        synchronized (state.callbackMonitor()) {
            if (!state.markCancelled()) return null;

            try {
                return invokeWithContext(state, method, delegateHandle,
                        arguments == null ? new Object[0] : arguments);
            } catch (Throwable failure) {
                recordException(state, failure);
                throw failure;
            } finally {
                endState(state);
            }
        }
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] arguments) {
        if ("toString".equals(method.getName())) {
            return "LangfuseTracingStreamingChatResponseHandler";
        }
        if ("hashCode".equals(method.getName())) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(method.getName())) {
            return arguments != null && arguments.length == 1 && proxy == arguments[0];
        }
        throw new UnsupportedOperationException(method.toString());
    }

    private Object invokeWithContext(InvocationState state,
                                     Method method,
                                     Object target,
                                     Object[] arguments) throws Throwable {
        Scope scope = makeCurrent(state);
        try {
            return invokeReflectively(method, target, arguments);
        } finally {
            closeScope(scope);
        }
    }

    private Object invokeReflectively(Method method, Object target, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private void runWithContext(InvocationState state, Runnable invocation) {
        Scope scope = makeCurrent(state);
        try {
            invocation.run();
        } finally {
            closeScope(scope);
        }
    }

    private Scope makeCurrent(InvocationState state) {
        try {
            return state.context().makeCurrent();
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not restore the LangChain4j streaming context", failure);
            return null;
        }
    }

    private void closeScope(Scope scope) {
        if (scope == null) return;
        try {
            scope.close();
        } catch (Throwable failure) {
            InstrumentationFailureSupport.rethrowIfFatal(failure);
            log.debug("Could not close the LangChain4j streaming context", failure);
        }
    }

    private void terminateWithError(InvocationState state, Throwable failure) {
        if (!state.markTerminal()) return;
        try {
            recordException(state, failure);
        } finally {
            endState(state);
        }
    }

    private void abortState(InvocationState state) {
        if (state.markTerminal()) endState(state);
    }

    private void recordException(InvocationState state, Throwable failure) {
        if (state.span() != null && failure != null) {
            InstrumentationFailureSupport.recordExceptionQuietly(langfuseOtel, state.span(), failure);
        }
    }

    private void endState(InvocationState state) {
        if (state.markEnded() && state.span() != null) {
            InstrumentationFailureSupport.endQuietly(state.span());
        }
    }

    private final class ContextPropagatingListener implements ChatModelListener {
        private final List<ChatModelListener> listeners;

        private ContextPropagatingListener(List<ChatModelListener> listeners) {
            this.listeners = listeners;
        }

        @Override
        public void onRequest(ChatModelRequestContext requestContext) {
            LangChain4jStreamingContext.putCurrentListenerAttributes(requestContext.attributes());
            notifyListeners(null, listener -> listener.onRequest(requestContext));
        }

        @Override
        public void onResponse(ChatModelResponseContext responseContext) {
            InvocationState state = currentState();
            if (state == null) {
                notifyListeners(null, listener -> listener.onResponse(responseContext));
                return;
            }
            LangChain4jStreamingContext.putListenerAttributes(responseContext.attributes(), state);
            if (!state.markListenerTerminal() || state.cancelled()) return;
            notifyListeners(state, listener -> listener.onResponse(responseContext));
        }

        @Override
        public void onError(ChatModelErrorContext errorContext) {
            InvocationState state = currentState();
            if (state == null) {
                notifyListeners(null, listener -> listener.onError(errorContext));
                return;
            }
            LangChain4jStreamingContext.putListenerAttributes(errorContext.attributes(), state);
            if (!state.markListenerTerminal() || state.cancelled()) return;
            notifyListeners(state, listener -> listener.onError(errorContext));
        }

        private void notifyListeners(InvocationState state, Consumer<ChatModelListener> invocation) {
            Scope scope = state == null ? null : makeCurrent(state);
            try {
                for (ChatModelListener listener : listeners) {
                    try {
                        invocation.accept(listener);
                    } catch (Exception failure) {
                        log.warn("An exception occurred during the invocation of a LangChain4j chat model "
                                + "listener. This exception has been ignored.", failure);
                    }
                }
            } finally {
                closeScope(scope);
            }
        }
    }

    private InvocationState currentState() {
        LangChain4jStreamingContext.State state = LangChain4jStreamingContext.currentState();
        return state instanceof InvocationState ? (InvocationState) state : null;
    }

    private static final class InvocationState implements LangChain4jStreamingContext.State {
        private final Span span;
        private final LangfuseTraceContext traceContext;
        private volatile Context context;
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final AtomicBoolean listenerTerminal = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final Object callbackMonitor = new Object();
        private final Object lifecycleMonitor = new Object();
        private boolean ended;

        private InvocationState(Span span, LangfuseTraceContext traceContext) {
            this.span = span;
            this.traceContext = traceContext;
        }

        private Span span() {
            return span;
        }

        @Override
        public Context context() {
            return context;
        }

        private void context(Context context) {
            this.context = context;
        }

        @Override
        public LangfuseTraceContext traceContext() {
            return traceContext;
        }

        @Override
        public boolean isActive() {
            synchronized (lifecycleMonitor) {
                return !ended;
            }
        }

        @Override
        public boolean tryStartTask() {
            synchronized (lifecycleMonitor) {
                if (ended) return false;
                return true;
            }
        }

        public boolean terminal() {
            return terminal.get();
        }

        private boolean markTerminal() {
            return terminal.compareAndSet(false, true);
        }

        private boolean markListenerTerminal() {
            return listenerTerminal.compareAndSet(false, true);
        }

        private boolean markCancelled() {
            if (!markTerminal()) return false;
            cancelled.set(true);
            return true;
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private Object callbackMonitor() {
            return callbackMonitor;
        }

        private boolean markEnded() {
            synchronized (lifecycleMonitor) {
                if (ended) return false;
                ended = true;
                return true;
            }
        }
    }

    // --- Helpers ---

    private String resolveSpanName() {
        return ModelSpanNameSupport.resolve(delegate, "chat", "ChatModel", "ChatLanguageModel");
    }

    private void setRequestAttributesOnSpan(Span span, ChatRequest request) {
        ChatRequestParameters effectiveParameters = defaultRequestParameters();
        if (request.parameters() != null) {
            effectiveParameters = effectiveParameters.overrideWith(request.parameters());
        }
        if (effectiveParameters.modelName() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, effectiveParameters.modelName());
        }
        if (effectiveParameters.temperature() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_TEMPERATURE, effectiveParameters.temperature());
        }
        if (effectiveParameters.maxOutputTokens() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MAX_TOKENS, (long) effectiveParameters.maxOutputTokens());
        }
        if (effectiveParameters.topP() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_TOP_P, effectiveParameters.topP());
        }

        List<ChatMessage> messages = request.messages();
        if (langfuseOtel.getContentCapturePolicy().isInputCaptureEnabled()
                && messages != null && !messages.isEmpty()) {
            langfuseOtel.recordInput(span, toJsonMessages(messages));
        }
    }

    private void setRequestAttributes(LangfuseGeneration gen, ChatRequest request) {
        if (gen == null) return;

        ChatRequestParameters effectiveParameters = defaultRequestParameters();
        if (request.parameters() != null) {
            effectiveParameters = effectiveParameters.overrideWith(request.parameters());
        }
        if (effectiveParameters.modelName() != null) gen.model(effectiveParameters.modelName());
        if (effectiveParameters.temperature() != null) gen.temperature(effectiveParameters.temperature());
        if (effectiveParameters.maxOutputTokens() != null) gen.maxTokens(effectiveParameters.maxOutputTokens());
        if (effectiveParameters.topP() != null) gen.topP(effectiveParameters.topP());

        List<ChatMessage> messages = request.messages();
        if (langfuseOtel.getContentCapturePolicy().isInputCaptureEnabled()
                && messages != null && !messages.isEmpty()) {
            langfuseOtel.recordInput(gen, toJsonMessages(messages));
        }
    }

    private void setResponseAttributesOnSpan(Span span, ChatResponse response,
                                             BoundedTextAccumulator accumulated) {
        if (response == null) return;

        if (response.modelName() != null) {
            span.setAttribute(LangfuseAttributes.GEN_AI_RESPONSE_MODEL, response.modelName());
            span.setAttribute(LangfuseAttributes.GEN_AI_REQUEST_MODEL, response.modelName());
        }

        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            if (usage.inputTokenCount() != null)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_INPUT_TOKENS, (long) usage.inputTokenCount());
            if (usage.outputTokenCount() != null)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_OUTPUT_TOKENS, (long) usage.outputTokenCount());
            if (usage.totalTokenCount() != null)
                span.setAttribute(LangfuseAttributes.GEN_AI_USAGE_TOTAL_TOKENS, (long) usage.totalTokenCount());
        }

        // Prefer complete response text, fall back to accumulated partials
        String output = null;
        if (response.aiMessage() != null && response.aiMessage().text() != null) {
            output = response.aiMessage().text();
        } else if (accumulated != null && !accumulated.overflowed() && accumulated.length() > 0) {
            output = accumulated.toString();
        }
        if (output != null) {
            langfuseOtel.recordOutput(span, output);
        }
    }

    private void setSyncResponseAttributes(LangfuseGeneration gen, ChatResponse response) {
        if (gen == null || response == null) return;

        if (response.modelName() != null) {
            gen.responseModel(response.modelName());
            gen.model(response.modelName());
        }
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            if (usage.inputTokenCount() != null) gen.inputTokens(usage.inputTokenCount());
            if (usage.outputTokenCount() != null) gen.outputTokens(usage.outputTokenCount());
            if (usage.totalTokenCount() != null) gen.totalTokens(usage.totalTokenCount());
        }
        if (response.aiMessage() != null && response.aiMessage().text() != null) {
            langfuseOtel.recordOutput(gen, response.aiMessage().text());
        }
    }

    private String toJsonMessages(List<ChatMessage> messages) {
        StringBuilder inputBuilder = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (i > 0) inputBuilder.append(",");
            inputBuilder.append("{\"role\":\"")
                    .append(msg.type().name().toLowerCase())
                    .append("\",\"content\":\"")
                    .append(JsonUtils.escapeJson(messageContent(msg)))
                    .append("\"}");
        }
        inputBuilder.append("]");
        return inputBuilder.toString();
    }

    private String messageContent(ChatMessage message) {
        if (message instanceof UserMessage) {
            UserMessage userMessage = (UserMessage) message;
            if (userMessage.hasSingleText()) {
                return userMessage.singleText();
            }
        }
        if (message instanceof SystemMessage) {
            return ((SystemMessage) message).text();
        }
        if (message instanceof AiMessage) {
            return ((AiMessage) message).text();
        }
        return String.valueOf(message);
    }

}
