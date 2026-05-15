package org.graalvm.shadowed.com.google.common.util.concurrent.internal;

/**
 * Stub for InternalFutures — the other Guava failureaccess class missing from
 * GraalVM 25 svm-wasm-guava.jar.  Mirrors the public Guava
 * com.google.common.util.concurrent.internal.InternalFutures shape.
 */
public final class InternalFutures {
    private InternalFutures() {}

    public static Throwable tryInternalFastPathGetFailure(InternalFutureFailureAccess future) {
        return future.tryInternalFastPathGetFailure();
    }
}
