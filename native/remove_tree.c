/* remove-tree DIR ...: remove each directory and all it holds, as `rm -rf` (a symbolic link removed,
 * never entered), after releasing the locks of every LMDB env in it (docs/failure-points.md#lmdb-robust-locks).
 * An env whose last user died without closing it keeps its SysV semaphore set until its next open,
 * and a deleted env is never opened again: deleting one without this left its set for good, and
 * enough of them filled kern.sysv.semmns, every later open failing in semget (ENOSPC).
 * Every lock.mdb (an env at its directory) and every NAME-lock (an env opened MDB_NOSUBDIR at NAME)
 * under DIR has its locks released by mdb_env_release_locks: what the env's last close does, for an
 * env nobody has open. An env a live process still holds is left to it (EBUSY): its files are deleted
 * under it, it keeps working on them, and its own last close removes the set (lmdb-sem case 7).
 * A separate process by design: an env must not have its locks released by a process that holds it
 * itself (its fcntl locks are the process's), and this one holds none.
 * bin/remove-tree is the shell's way in; seon.fx/remove-tree! calls this binary. Prints nothing when
 * all is well; a lock file it could not read is named on stderr. Exits as rm does. */
#define _XOPEN_SOURCE 700
#include <lmdb.h>
#include <ftw.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>

static int release(const char *path, const struct stat *st, int type, struct FTW *f) {
  (void)st;
  if (type != FTW_F) return 0;
  const char *name = path + f->base;
  size_t n = strlen(path), k = strlen(name);
  char env[4096];
  int r = MDB_SUCCESS;
  if (!strcmp(name, "lock.mdb")) {
    if (f->base == 0 || (size_t)f->base > sizeof env) return 0;
    memcpy(env, path, f->base - 1); env[f->base - 1] = 0;
    r = mdb_env_release_locks(env, 0);
  } else if (k > 5 && !strcmp(path + n - 5, "-lock") && n - 5 < sizeof env) {
    memcpy(env, path, n - 5); env[n - 5] = 0;
    r = mdb_env_release_locks(env, MDB_NOSUBDIR);
    if (r == MDB_INVALID) r = MDB_SUCCESS;	/* a file of that name that is no LMDB lock file */
  }
  if (r && r != EBUSY) fprintf(stderr, "remove-tree: %s: %s\n", path, mdb_strerror(r));
  return 0;
}

int main(int argc, char **argv) {
  if (argc < 2) { fprintf(stderr, "usage: remove-tree DIR ...\n"); return 2; }
  for (int i = 1; i < argc; i++) nftw(argv[i], release, 64, FTW_PHYS);
  char *rm[argc + 3];
  rm[0] = "rm"; rm[1] = "-rf"; rm[2] = "--";
  for (int i = 1; i < argc; i++) rm[i + 2] = argv[i];
  rm[argc + 2] = NULL;
  execvp("rm", rm);
  perror("remove-tree: rm");
  return 1;
}
