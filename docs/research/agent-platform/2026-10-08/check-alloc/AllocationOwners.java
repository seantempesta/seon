import java.nio.file.Path;
import java.util.*;
import jdk.jfr.consumer.*;

/** Aggregate JFR allocation samples; bytes are sample weights, not exact owner allocation. */
class AllocationOwners {
    public static void main(String[] args) throws Exception {
        String required = args.length > 1 ? args[1] : "";
        Map<String, Long> nearest = new HashMap<>(), callers = new HashMap<>(), inclusive = new HashMap<>();
        Map<String, Long> threads = new HashMap<>(), leaves = new HashMap<>();
        long bytes = 0, events = 0, truncated = 0, largest = 0;
        String first = null, last = null;
        try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!event.getEventType().getName().equals("jdk.ObjectAllocationSample")) continue;
                if (args.length > 2 && event.getStartTime().isBefore(java.time.Instant.parse(args[2]))) continue;
                if (args.length > 3 && event.getStartTime().isAfter(java.time.Instant.parse(args[3]))) continue;
                RecordedStackTrace trace = event.getStackTrace();
                List<String> frames = trace == null ? List.of() : trace.getFrames().stream()
                    .map(f -> f.getMethod().getType().getName()).toList();
                if (!required.isEmpty() && frames.stream().noneMatch(f -> f.contains(required))) continue;
                long weight = event.getLong("weight");
                events++; bytes += weight; largest = Math.max(largest, weight);
                if (trace != null && trace.isTruncated()) truncated++;
                String time = event.getStartTime().toString();
                if (first == null || time.compareTo(first) < 0) first = time;
                if (last == null || time.compareTo(last) > 0) last = time;
                List<String> owners = frames.stream().filter(f -> f.startsWith("seon.") || f.startsWith("my."))
                    .filter(f -> !f.startsWith("seon.instrument$") && !f.startsWith("seon.profile$"))
                    .map(f -> { int end = f.indexOf('$', f.indexOf('$') + 1); return end < 0 ? f : f.substring(0, end); })
                    .distinct().toList();
                nearest.merge(owners.isEmpty() ? "<unattributed>" : owners.getFirst(), weight, Long::sum);
                callers.merge(owners.stream().filter(f -> !f.startsWith("seon.db$") && !f.startsWith("seon.cache$")
                        && !f.equals("seon.call_preparation$preparing") && !f.startsWith("seon.schema$call_with_projection"))
                    .findFirst().orElse("<unattributed>"), weight, Long::sum);
                for (String owner : owners) inclusive.merge(owner, weight, Long::sum);
                RecordedThread thread = event.getThread();
                threads.merge(thread.getJavaThreadId() + ":" + thread.getJavaName(), weight, Long::sum);
                leaves.merge(frames.isEmpty() ? "<no stack>" : frames.getFirst(), weight, Long::sum);
            }
        }
        System.out.printf("events=%d weighted_bytes=%d truncated=%d largest_sample=%d first=%s last=%s%n",
            events, bytes, truncated, largest, first, last);
        for (var section : List.of(Map.entry("NEAREST", nearest), Map.entry("CALLER", callers),
                                    Map.entry("INCLUSIVE_NOT_ADDITIVE", inclusive), Map.entry("THREAD", threads),
                                    Map.entry("LEAF", leaves))) {
            System.out.println(section.getKey());
            section.getValue().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(30).forEach(e -> System.out.printf("%.6f GB\t%s%n", e.getValue() / 1e9, e.getKey()));
        }
    }
}
