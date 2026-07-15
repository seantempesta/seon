package seon;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.RT;

/** Stable Java entry point for the source-loaded database server. */
public final class DatabaseServerMain {
    private DatabaseServerMain() {}

    public static void main(String[] args) {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("seon.db.server"));
        IFn serverMain = Clojure.var("seon.db.server", "-main");
        serverMain.applyTo(RT.seq(args));
    }
}
