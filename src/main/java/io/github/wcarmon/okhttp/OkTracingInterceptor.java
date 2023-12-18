package io.github.wcarmon.okhttp;

import io.github.wcarmon.otel.SpanUtils;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import java.util.Objects;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.Nullable;

/** Intercept OkHTTP client requests, wrap in span, add request headers to span */
public final class OkTracingInterceptor implements Interceptor {

    private static final String DEFAULT_SPAN_NAME = "okhttp-call >>";

    private final ContextPropagators propagators;
    private final String spanName;
    private final Tracer tracer;

    private OkTracingInterceptor(ContextPropagators propagators, String spanName, Tracer tracer) {

        Objects.requireNonNull(propagators, "propagators is required and null.");
        Objects.requireNonNull(tracer, "tracer is required and null.");

        this.propagators = propagators;
        this.spanName = (spanName == null || spanName.isBlank()) ? DEFAULT_SPAN_NAME : spanName;
        this.tracer = tracer;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Nullable
    private static String abbreviate(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        if (raw.length() <= maxLength) {
            return raw;
        }

        if (maxLength < 8) {
            throw new IllegalArgumentException("There's no point abbreviating short strings");
        }

        return raw.substring(0, maxLength - 3) + "...";
    }

    @Override
    public Response intercept(Interceptor.Chain chain) {

        final var originalRequest = chain.request();
        final var requestBuilder = originalRequest.newBuilder();

        final var child = buildSpanForRequest(originalRequest, getCurrentSpanContext());
        try (var ignored = child.makeCurrent()) {

            child.setAttribute("thread.id.before", Thread.currentThread().getId());
            child.setAttribute("thread.name.before", Thread.currentThread().getName());

            // W3CTraceContextPropagator uses to retrieve active span
            final var ctx = Context.current();

            propagators
                    .getTextMapPropagator()
                    .inject(
                            ctx,
                            requestBuilder,
                            (builder, key, value) -> {
                                if (builder == null) {
                                    return;
                                }

                                if (key == null || key.isBlank()) {
                                    throw new IllegalArgumentException("key is required");
                                }

                                builder.header(key, value);
                            });

            final var tracedRequest = requestBuilder.build();

            final var resp = chain.proceed(tracedRequest);

            child.setAttribute("http.status_code", resp.code());
            child.setAttribute("thread.id.after", Thread.currentThread().getId());
            child.setAttribute("thread.name.after", Thread.currentThread().getName());

            return resp;

        } catch (Throwable t) {
            throw SpanUtils.record(child, t);

        } finally {
            child.end();
        }
    }

    private Span buildSpanForRequest(Request req, Context parent) {
        Objects.requireNonNull(parent, "parent is required and null.");
        Objects.requireNonNull(req, "req is required and null.");

        final var span =
                tracer.spanBuilder(spanName)
                        .setAttribute("http.method", req.method())
                        .setAttribute("http.url", req.url().toString())
                        .setParent(parent)
                        .setSpanKind(SpanKind.CLIENT)
                        .startSpan();

        req.headers()
                .forEach(
                        pair ->
                                span.setAttribute(
                                        "http.request.header." + pair.getFirst(),
                                        abbreviate(pair.getSecond(), 96)));

        return span;
    }

    private Context getCurrentSpanContext() {
        return Context.current();
    }

    public static class Builder {

        private ContextPropagators propagators;
        private String spanName;
        private Tracer tracer;

        Builder() {}

        public OkTracingInterceptor build() {
            return new OkTracingInterceptor(this.propagators, this.spanName, this.tracer);
        }

        public Builder propagators(ContextPropagators propagators) {
            this.propagators = propagators;
            return this;
        }

        public Builder spanName(String spanName) {
            this.spanName = spanName;
            return this;
        }

        public Builder tracer(Tracer tracer) {
            this.tracer = tracer;
            return this;
        }
    }
}
