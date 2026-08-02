package seon;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.RT;

/** Thin Java entry point for the fresh standalone artifact. */
public final class ArtifactMain {
    private ArtifactMain() {}

    public static void main(String[] args) {
        long namespaceLoadStarted = System.nanoTime();
        System.setProperty(
                "seon.artifact.namespace-load-started-nanos",
                Long.toString(namespaceLoadStarted));
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("seon.artifact"));
        System.setProperty(
                "seon.artifact.namespace-load-completed-nanos",
                Long.toString(System.nanoTime()));
        IFn artifactMain = Clojure.var("seon.artifact", "-main");
        artifactMain.applyTo(RT.seq(args));
    }
}
