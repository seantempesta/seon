// watch ROOT...: every change FSEvents reports under the roots, as it happens, on stdout: one
// record per event, its kind letter then the absolute path, ended by NUL (a path may hold a
// newline; none holds NUL). Kinds: 'p' a path changed (created, written, renamed, removed);
// 's' the subtree at path must be read again (MustScanSubDirs: events under it were coalesced
// or dropped, by the kernel or by this process); 'r' a root itself moved or was removed; 'w'
// (first, once) the stream is registered, so a change from here on is reported. No clock:
// latency 0, NoDefer, FileEvents, on a dispatch queue (seon.watch reads it; its edge in
// docs/failure-points.md). It exits when its parent does (a GCD process source: kqueue
// EVFILT_PROC), never on a timer or on stdin.
//
// FSEventStreamStart returns before fseventsd delivers to the stream, so a change made just
// after it can be lost (1 in 600 writes right after start, measured under load). Registration
// is proved as Watchman proves it, with a synchronization cookie: the stream also watches a
// directory of its own, a file written there after the start comes back as an event only once
// the stream is registered, and only then is 'w' written. The cookie directory is removed
// then, and nothing under it is ever reported; a change to a root before 'w' precedes the
// promise and is not reported either.
#include <CoreServices/CoreServices.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void put(char kind, const char *path) { putchar(kind); fputs(path, stdout); putchar('\0'); }

static char cookie_dir[PATH_MAX], cookie[PATH_MAX + 8];
static const char *first_root;
static int ready;

static int under_cookie(const char *p) {
  size_t n = strlen(cookie_dir);
  return strncmp(p, cookie_dir, n) == 0 && (p[n] == '\0' || p[n] == '/');
}

static void changed(ConstFSEventStreamRef s, void *info, size_t n, void *paths,
                    const FSEventStreamEventFlags flags[], const FSEventStreamEventId ids[]) {
  char **p = paths;
  for (size_t i = 0; i < n; i++) {
    FSEventStreamEventFlags f = flags[i];
    if (f & kFSEventStreamEventFlagHistoryDone) continue;
    if (under_cookie(p[i])) {
      if (!ready) { ready = 1; put('w', first_root); unlink(cookie); rmdir(cookie_dir); }
      continue;
    }
    if (!ready) continue;
    if (f & kFSEventStreamEventFlagRootChanged) put('r', p[i]);
    else if (f & (kFSEventStreamEventFlagMustScanSubDirs | kFSEventStreamEventFlagUserDropped | kFSEventStreamEventFlagKernelDropped)) put('s', p[i]);
    else put('p', p[i]);
  }
  fflush(stdout);
}

int main(int argc, char **argv) {
  if (argc < 2) { fputs("usage: watch ROOT...\n", stderr); return 64; }
  pid_t parent = getppid();
  if (parent == 1) return 0;
  first_root = argv[1];
  // the cookie's directory, by its real path: FSEvents reports /private/var/..., not /var/...
  char tmp[PATH_MAX];
  size_t tl = confstr(_CS_DARWIN_USER_TEMP_DIR, tmp, sizeof tmp);
  if (tl == 0 || tl > sizeof tmp - 32) { fputs("watch: no user temp dir\n", stderr); return 70; }
  strcat(tmp, "seon-watch-cookie-XXXXXX");
  if (!mkdtemp(tmp) || !realpath(tmp, cookie_dir)) { perror("watch: cookie dir"); return 73; }
  snprintf(cookie, sizeof cookie, "%s/cookie", cookie_dir);
  CFMutableArrayRef roots = CFArrayCreateMutable(NULL, 0, &kCFTypeArrayCallBacks);
  CFStringRef cr = CFStringCreateWithCString(NULL, cookie_dir, kCFStringEncodingUTF8);
  CFArrayAppendValue(roots, cr);
  CFRelease(cr);
  for (int i = 1; i < argc; i++) {
    CFStringRef r = CFStringCreateWithCString(NULL, argv[i], kCFStringEncodingUTF8);
    CFArrayAppendValue(roots, r);
    CFRelease(r);
  }
  FSEventStreamRef s = FSEventStreamCreate(NULL, changed, NULL, roots, kFSEventStreamEventIdSinceNow, 0.0,
                                           kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagNoDefer |
                                           kFSEventStreamCreateFlagWatchRoot);
  dispatch_queue_t q = dispatch_queue_create("seon.watch", NULL);
  FSEventStreamSetDispatchQueue(s, q);
  if (!FSEventStreamStart(s)) { fputs("watch: FSEventStreamStart refused\n", stderr); rmdir(cookie_dir); return 69; }
  dispatch_source_t gone = dispatch_source_create(DISPATCH_SOURCE_TYPE_PROC, parent, DISPATCH_PROC_EXIT, q);
  dispatch_source_set_event_handler(gone, ^{ unlink(cookie); rmdir(cookie_dir); exit(0); });
  dispatch_resume(gone);
  // the parent may have died between getppid and the source's creation: the source then never fires
  if (getppid() != parent) { rmdir(cookie_dir); return 0; }
  // the cookie: its event, whenever fseventsd delivers it, is the stream registered ('w')
  FILE *c = fopen(cookie, "w");
  if (!c) { perror("watch: cookie"); rmdir(cookie_dir); return 73; }
  fclose(c);
  dispatch_main();
}
