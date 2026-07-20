// Sets the sci JIT runtime opt-out BEFORE the bundle loads, then loads it.
if (process.env.SCI_DISABLE_JIT) globalThis.SCI_DISABLE_JIT = true;
require("./out/main.js");
