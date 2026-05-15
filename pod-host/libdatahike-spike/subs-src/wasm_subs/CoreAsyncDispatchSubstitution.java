package wasm_subs;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Substitute clojure.core.async.impl.dispatch/run with a synchronous direct
 * invocation under WASM.
 *
 * Background: Web Image is single-threaded.  When datahike's index code
 * (e.g. datahike.index.persistent-set/CachedStorage.restore) calls into
 * konserve's async API, the call chain reaches clojure.core.async.impl.dispatch/run,
 * which dereferences the `executor` delay to spin up a ThreadPoolExecutor.
 * That executor creation drags JavaMonitorQueuedSynchronizer.parkAndCheckInterrupt
 * (the @Platforms-gated NATIVE_ONLY substitution) and array-instantiation
 * reflection paths into reachability, both of which fail the WASM build.
 *
 * Under WASM single-threaded execution there is exactly one thread; "dispatch
 * to a thread pool" reduces to "run on the caller thread."  This substitution
 * makes that explicit: no thread-pool, no executor delay, no monitor-park
 * machinery in the reachable graph.
 *
 * Clojure compiles `(defn run [r] ...)` to a class named `<ns>$run` with both
 * `invokeStatic(Object)` (static dispatch) and `invoke(Object)` (via IFn).
 */
@TargetClass(className = "clojure.core.async.impl.dispatch$run")
final class Target_clojure_core_async_impl_dispatch_run {

    @Substitute
    public static Object invokeStatic(Object r) {
        ((Runnable) r).run();
        return null;
    }

    @Substitute
    public Object invoke(Object r) {
        ((Runnable) r).run();
        return null;
    }
}

public final class CoreAsyncDispatchSubstitution {
    private CoreAsyncDispatchSubstitution() {}
}
