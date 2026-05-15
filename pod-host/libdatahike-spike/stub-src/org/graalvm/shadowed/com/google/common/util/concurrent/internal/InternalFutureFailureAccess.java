package org.graalvm.shadowed.com.google.common.util.concurrent.internal;

/**
 * Stub for the shadowed Guava class missing from GraalVM 25's svm-wasm-guava.jar.
 *
 * The shadowed AbstractFutureState (in the same wrapped-guava module) extends
 * this class but the JAR shipped with GraalVM 25.0.2 LTS GA AND 25e1-ea.25 does
 * not include it.  We inject the class via --patch-module=org.graalvm.wrapped.google.guava=stub-classes.
 *
 * Mirrors com.google.common.util.concurrent.internal.InternalFutureFailureAccess.
 */
public abstract class InternalFutureFailureAccess {
    protected InternalFutureFailureAccess() {}

    protected abstract Throwable tryInternalFastPathGetFailure();
}
