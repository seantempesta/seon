package seon;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.RT;

/** Thin Java entry point for the fresh standalone artifact. */
public final class ArtifactMain {
    private ArtifactMain() {}

    public static void main(String[] args) {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("seon.artifact"));
        IFn artifactMain = Clojure.var("seon.artifact", "-main");
        artifactMain.applyTo(RT.seq(args));
    }
}
