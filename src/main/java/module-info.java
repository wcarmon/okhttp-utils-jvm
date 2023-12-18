module io.github.wcarmon.okhttp {
    exports io.github.wcarmon.okhttp;

    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires io.github.wcarmon.otel;
    requires io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires kotlin.stdlib;
    requires okhttp3;
    requires org.jetbrains.annotations;
}
