package io.github.wcarmon.okhttp;

import java.io.IOException;
import java.util.Objects;
import okhttp3.Interceptor;
import okhttp3.Response;

/** Adds token header to requests, when present */
public final class AuthTokenInterceptor implements Interceptor {

    /** TokenStore which allows read/write token */
    private final TokenStore tokenStore;

    public AuthTokenInterceptor(TokenStore tokenStore) {
        Objects.requireNonNull(tokenStore, "tokenStore is required and null.");

        this.tokenStore = tokenStore;
    }

    /** Apply token from TokenStore to the request as Authorization header (when present). */
    @Override
    public Response intercept(Interceptor.Chain chain) throws IOException {
        final var originalRequest = chain.request();

        final var token = tokenStore.getToken();
        if (token.isBlank()) {
            return chain.proceed(originalRequest);
        }

        final var requestBuilder = originalRequest.newBuilder();
        requestBuilder.header("Authorization", "Bearer " + token);

        return chain.proceed(requestBuilder.build());
    }
}
