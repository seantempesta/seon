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
// after it was lost (1 in 600 writes right after start, measured under load), and fseventsd's
// history holds no change that recent to replay. The stream starts from the event id current
// when it is made, and fseventsd ends the (empty) history it sends with an event flagged
// HistoryDone, after which live events follow: that sentinel is 'w'.
#include <CoreServices/CoreServices.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

static void put(char kind, const char *path) { putchar(kind); fputs(path, stdout); putchar('\0'); }

static const char *first_root;
static int ready;

static void changed(ConstFSEventStreamRef s, void *info, size_t n, void *paths,
                    const FSEventStreamEventFlags flags[], const FSEventStreamEventId ids[]) {
  char **p = paths;
  for (size_t i = 0; i < n; i++) {
    FSEventStreamEventFlags f = flags[i];
    if (f & kFSEventStreamEventFlagHistoryDone) { ready = 1; put('w', first_root); continue; }
    // a change replayed from before the sentinel precedes the promise: seon.watch reads 'w' first
    if (!ready) continue;
    if (f & kFSEventStreamEventFlagRootChanged) put('r', p[i]);
    else if (f & (kFSEventStreamEventFlagMustScanSubDirs | kFSEventStreamEventFlagUserDropped | kFSEventStreamEventFlagKernelDropped)) put('s', p[i]);
    else put('p', p[i]);
  }
  fflush(stdout);
}

int main(int argc, char **argv) {
  if (argc < 2) { fputs("usage: watch ROOT...\n", stderr); return 64; }
  first_root = argv[1];
  pid_t parent = getppid();
  if (parent == 1) return 0;
  CFMutableArrayRef roots = CFArrayCreateMutable(NULL, 0, &kCFTypeArrayCallBacks);
  for (int i = 1; i < argc; i++) {
    CFStringRef r = CFStringCreateWithCString(NULL, argv[i], kCFStringEncodingUTF8);
    CFArrayAppendValue(roots, r);
    CFRelease(r);
  }
  FSEventStreamRef s = FSEventStreamCreate(NULL, changed, NULL, roots, FSEventsGetCurrentEventId(), 0.0,
                                           kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagNoDefer |
                                           kFSEventStreamCreateFlagWatchRoot);
  dispatch_queue_t q = dispatch_queue_create("seon.watch", NULL);
  FSEventStreamSetDispatchQueue(s, q);
  if (!FSEventStreamStart(s)) { fputs("watch: FSEventStreamStart refused\n", stderr); return 69; }
  dispatch_source_t gone = dispatch_source_create(DISPATCH_SOURCE_TYPE_PROC, parent, DISPATCH_PROC_EXIT, q);
  dispatch_source_set_event_handler(gone, ^{ exit(0); });
  dispatch_resume(gone);
  // the parent may have died between getppid and the source's creation: the source then never fires
  if (getppid() != parent) return 0;
  dispatch_main();
}
