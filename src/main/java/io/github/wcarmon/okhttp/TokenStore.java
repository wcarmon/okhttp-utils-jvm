package io.github.wcarmon.okhttp;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wcarmon.otel.SpanUtils;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.jetbrains.annotations.Nullable;

/** Maintains JSON web token for an HTTP client Reads/Write to a file */
public final class TokenStore {

    static final String TOKEN_PROPERTY = "token";
    static final String UUID_PROPERTY = "userUuid";
    private final Path credentialCachePath;
    private final ObjectMapper objectMapper;
    private final Lock stateLock;
    private final Tracer tracer;

    private String token;
    private UUID userUuid;

    private TokenStore(ObjectMapper objectMapper, Path credentialCachePath, Tracer tracer) {
        requireNonNull(credentialCachePath, "credentialCachePath is required and null.");
        requireNonNull(objectMapper, "objectMapper is required and null.");
        requireNonNull(tracer, "tracer is required and null.");

        if (Files.exists(credentialCachePath)) {
            if (Files.isDirectory(credentialCachePath)) {
                throw new IllegalArgumentException(
                        "authCacheFile cannot be a directory: " + credentialCachePath);
            }

            if (!Files.isRegularFile(credentialCachePath)) {
                throw new IllegalArgumentException(
                        "authCacheFile must be a regular file: " + credentialCachePath);
            }
        }

        this.credentialCachePath = credentialCachePath.normalize();
        this.objectMapper = objectMapper;
        this.stateLock = new ReentrantLock();
        this.token = "";
        this.tracer = tracer;
        this.userUuid = null;

        SpanUtils.runInARootSpan(
                tracer, "tokenStore.new", span -> loadStateFromFile(span.getSpanContext()));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return current JSON Web Token, or empty string
     */
    public String getToken() {
        this.stateLock.lock();
        try {
            return token;

        } finally {
            this.stateLock.unlock();
        }
    }

    /**
     * @return current User's UUID or null
     */
    @Nullable
    public UUID getUserUuid() {
        this.stateLock.lock();
        try {
            return userUuid;

        } finally {
            this.stateLock.unlock();
        }
    }

    /**
     * Reads token from configured file, stores in memory
     *
     * @param ctx
     */
    public void loadStateFromFile(SpanContext ctx) {
        requireNonNull(ctx, "ctx is required and null.");

        SpanUtils.runInChildSpan(
                tracer,
                "tokenStore.load",
                ctx,
                span -> {
                    span.setAttribute("credentialCachePath", credentialCachePath.toString());

                    this.stateLock.lock();
                    try {

                        final var fileExists = Files.exists(credentialCachePath);
                        span.setAttribute("file.exists", fileExists);

                        if (!fileExists) {
                            return;
                        }

                        final var raw = Files.readAllBytes(credentialCachePath);
                        Map<String, String> state =
                                objectMapper.readValue(raw, new TypeReference<>() {});

                        final var tkn = state.get(TOKEN_PROPERTY);
                        final var uu = UUID.fromString(state.get(UUID_PROPERTY));
                        updateToken(span.getSpanContext(), tkn, uu);

                    } finally {
                        this.stateLock.unlock();
                    }
                });
    }

    public void saveStateToFile(SpanContext ctx) {
        requireNonNull(ctx, "ctx is required and null.");

        SpanUtils.runInChildSpan(
                tracer,
                "tokenStore.save",
                ctx,
                span -> {
                    this.stateLock.lock();
                    try {
                        if (token.isBlank()) {
                            span.addEvent("token is blank");
                            span.addEvent("skipping save, since token is blank");
                            return;
                        }

                        final var state =
                                Map.of(TOKEN_PROPERTY, token, UUID_PROPERTY, userUuid.toString());

                        Files.createDirectories(credentialCachePath.getParent());

                        span.setAttribute("credentialCachePath", credentialCachePath.toString());
                        Files.write(
                                credentialCachePath,
                                objectMapper.writeValueAsBytes(state),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE);

                    } finally {
                        this.stateLock.unlock();
                    }
                });
    }

    /**
     * Replace/Update JSON Web Token.
     *
     * @param token
     */
    public void updateToken(SpanContext ctx, String token, UUID userUuid) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required");
        }

        requireNonNull(ctx, "ctx is required and null.");
        requireNonNull(userUuid, "userUuid is required and null.");

        if (!Objects.equals(token, token.strip())) {
            throw new IllegalArgumentException("token must be trimmed");
        }

        SpanUtils.runInChildSpan(
                tracer,
                "tokenStore.update",
                ctx,
                span -> {
                    this.stateLock.lock();
                    try {
                        this.token = token;
                        this.userUuid = userUuid;

                    } finally {
                        this.stateLock.unlock();
                    }

                    saveStateToFile(span.getSpanContext());
                });
    }

    public static class Builder {

        private Path credentialCachePath;
        private ObjectMapper objectMapper;
        private Tracer tracer;

        Builder() {}

        public TokenStore build() {
            return new TokenStore(this.objectMapper, this.credentialCachePath, this.tracer);
        }

        public Builder credentialCachePath(Path credentialCachePath) {
            this.credentialCachePath = credentialCachePath;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder tracer(Tracer tracer) {
            this.tracer = tracer;
            return this;
        }
    }
}
