/* lmdb-kill STORE: the robust-lock proof (docs/failure-points.md#lmdb-robust-locks). This process
 * holds STORE's env open; a child SIGKILLs itself inside a write txn; then a new process and
 * this one each write. With LMDB's POSIX-semaphore locks (macOS's default) both writes block
 * forever on the dead child's writer lock, and this check shows as running, never ending;
 * with the robust build (MDB_USE_ROBUST) the kernel releases the lock and both commit.
 * Prints LMDB-KILL OK and exits 0 only when both writes committed and the child's did not. */
#include <lmdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <sys/wait.h>

static MDB_env *open_env(const char *p) {
  MDB_env *e; int r = mdb_env_create(&e);
  if (!r) r = mdb_env_set_mapsize(e, 1 << 26);
  if (!r) r = mdb_env_open(e, p, MDB_NOSUBDIR | MDB_NOTLS, 0664);
  if (r) { printf("env_open %s: %s\nLMDB-KILL FAILED\n", p, mdb_strerror(r)); exit(1); }
  return e;
}

/* put key k; die: SIGKILL this process inside the txn instead of committing */
static int put(MDB_env *e, const char *k, int die) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {strlen(k), (void *)k}, V = {1, "v"};
  int r = mdb_txn_begin(e, NULL, 0, &t);
  if (!r) r = mdb_dbi_open(t, NULL, 0, &d);
  if (!r) r = mdb_put(t, d, &K, &V, 0);
  if (r) return r;
  if (die) { printf("child %d: inside a write txn, SIGKILL\n", getpid()); kill(getpid(), SIGKILL); }
  return mdb_txn_commit(t);
}

static int has(MDB_env *e, const char *k) {
  MDB_txn *t; MDB_dbi d; MDB_val K = {strlen(k), (void *)k}, V;
  mdb_txn_begin(e, NULL, MDB_RDONLY, &t); mdb_dbi_open(t, NULL, 0, &d);
  int r = mdb_get(t, d, &K, &V); mdb_txn_abort(t); return r == 0;
}

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  if (argc != 2) { printf("usage: lmdb-kill STORE\n"); return 2; }
  const char *p = argv[1];
  MDB_env *e = open_env(p);
  int r0 = put(e, "before", 0), st;
  pid_t k = fork();
  if (!k) { put(open_env(p), "killed", 1); _exit(0); }
  waitpid(k, &st, 0);
  printf("child: signaled %d, signal %d\n", WIFSIGNALED(st), WTERMSIG(st));
  pid_t w = fork();
  if (!w) { int r = put(open_env(p), "after", 0); printf("a new process writes: %s\n", r ? mdb_strerror(r) : "committed"); _exit(r ? 1 : 0); }
  waitpid(w, &st, 0);
  int r1 = put(e, "holder", 0);
  printf("the holder writes: %s\n", r1 ? mdb_strerror(r1) : "committed");
  int ok = !r0 && WIFEXITED(st) && !WEXITSTATUS(st) && !r1 && has(e, "after") && has(e, "holder") && !has(e, "killed");
  printf(ok ? "LMDB-KILL OK\n" : "LMDB-KILL FAILED\n");
  return ok ? 0 : 1;
}
