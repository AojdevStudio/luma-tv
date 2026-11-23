import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Bounded model check of UpNextWidget's refresh ordering, run on the real source.
 *
 * The checker cuts the members between UpNextWidget's constructor and load() out of the source file, word for word,
 * and compiles them into a replay class beside small fakes: a Context, WidgetBoard.IO, load(), post() and show().
 * It then drives that code through every order of these steps, up to the bound, and replays each order from scratch:
 *   refresh   the UI thread calls refresh() (Home comes back from an app)
 *   write     an app writes a newer Watch Next row (the data version goes up by one)
 *   start     a WidgetBoard.IO worker takes the oldest queued task and load() reads the current version
 *   finish    a running task returns from load() and its post() queues work for the UI thread
 *   ui        the UI thread runs the oldest posted runnable (show() records the version it draws)
 * WidgetBoard.IO has two workers, so at most two tasks run at once and tasks start in the order they were queued.
 *
 * Exit 0: every property held on every schedule. Exit 1: a counterexample. Exit 2: no result.
 */
public final class UpNextRefreshCheck {
    static final String DEFAULT_SOURCE = "src/dev/luma/tv/UpNextWidget.java";
    static final int WORKERS = 2;
    static final long STEP_TIMEOUT_SECONDS = 10;

    static final String ONE_READ = "one_read_at_a_time";
    static final String NO_ROLLBACK = "never_shows_older";
    static final String NEWEST_LANDS = "newest_request_lands";
    static final String READ_BEFORE_POSTERS = "pending_read_before_posters";
    static final Map<String, String> PROPERTIES = new LinkedHashMap<>();
    static {
        PROPERTIES.put(READ_BEFORE_POSTERS, "In the two-poster replay, the pending provider read is queued before posters from the previous draw.");
        PROPERTIES.put(ONE_READ, "At most one Up Next read is queued or running, so reads never share lastUsed or race each other.");
        PROPERTIES.put(NO_ROLLBACK, "Up Next never draws a Watch Next version older than one it already drew.");
        PROPERTIES.put(NEWEST_LANDS, "Once all work is done, Up Next shows data at least as new as the data at the last refresh request.");
    }

    /** A scratch change to the cut-out refresh code that must break the named property. */
    record Mutant(String name, String property, String find, String replace) {}
    static final List<Mutant> MUTANTS = List.of(
        new Mutant("drop the in-flight guard", ONE_READ, "if(loading){stale=true;return;}", ""),
        new Mutant("drop the in-flight guard", NO_ROLLBACK, "if(loading){stale=true;return;}", ""),
        new Mutant("drop the rerun after a stale request", NEWEST_LANDS, "if(stale){stale=false;refresh();}", ""),
        new Mutant("clear the flag before the read", ONE_READ, "ArrayList<Item> items=load(app);", "loading=false;ArrayList<Item> items=load(app);"));

    static final class NoResult extends Exception { NoResult(String m) { super(m); } }

    public static void main(String[] args) throws Exception {
        String source = DEFAULT_SOURCE;
        boolean selfCheck = false;
        int refreshes = 3, writes = 2;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--source" -> source = args[++i];
                case "--self-check" -> selfCheck = true;
                case "--refreshes" -> refreshes = Integer.parseInt(args[++i]);
                case "--writes" -> writes = Integer.parseInt(args[++i]);
                case "--help" -> { System.out.println("usage: java UpNextRefreshCheck.java [--source UpNextWidget.java] [--refreshes 3] [--writes 2] [--self-check]"); return; }
                default -> { System.out.println("no result: unknown argument " + args[i]); System.exit(2); }
            }
        }
        try {
            String region = cutRefreshRegion(Files.readString(Paths.get(source)));
            System.out.println("source  " + source);
            System.out.println("region  sha256 " + sha256(region) + " (constructor end to load())");
            System.out.println("bound   " + refreshes + " refresh requests, " + writes + " Watch Next writes, " + WORKERS + " IO workers");
            Result result = check(region, refreshes, writes);
            result.print();
            boolean pass = result.failures.isEmpty();
            if (selfCheck) {
                boolean caught = true;
                for (Mutant m : MUTANTS) {
                    if (!region.contains(m.find)) throw new NoResult("self-check anchor missing for mutant '" + m.name + "': " + m.find);
                    Result r = check(region.replace(m.find, m.replace), refreshes, writes);
                    boolean hit = r.failures.containsKey(m.property);
                    caught &= hit;
                    System.out.println((hit ? "caught  " : "MISSED  ") + "mutant '" + m.name + "' breaks " + m.property);
                }
                if (!caught) throw new NoResult("self-check: a mutant was not caught, so this check cannot be trusted");
            }
            System.out.println(pass ? "PASS within the bound above" : "FAIL: counterexample above");
            System.exit(pass ? 0 : 1);
        } catch (NoResult e) {
            System.out.println("no result: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.out.println("no result: the checker itself failed");
            e.printStackTrace(System.out);
            System.exit(2);
        }
    }

    /** The members after the constructor and before load(): in the real file, refresh() and the state it keeps. */
    static String cutRefreshRegion(String file) throws NoResult {
        String ctor = "UpNextWidget(Context context,WidgetBoard.Host host){";
        int at = file.indexOf(ctor);
        if (at < 0 || file.indexOf(ctor, at + 1) >= 0) throw new NoResult("expected one constructor '" + ctor + "'");
        int depth = 0, i = at + ctor.length() - 1;
        for (; i < file.length(); i++) {
            char c = file.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) break;
        }
        int start = file.indexOf('\n', i) + 1;
        int end = file.indexOf("    private ArrayList<Item> load(", start);
        if (start <= 0 || end < 0) throw new NoResult("could not find load() after the constructor");
        String region = file.substring(start, end);
        if (!region.contains("void refresh()")) throw new NoResult("refresh() is no longer between the constructor and load()");
        return region;
    }

    static String sha256(String s) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", b));
        return out.substring(0, 16);
    }

    /** Fakes keep the names the real code uses, so the cut-out text compiles unchanged. */
    static String replaySource(String region) {
        return """
            import java.util.*;
            import java.util.concurrent.Executor;
            import java.util.function.*;
            abstract class Widget { void refresh() {} }
            final class Context { Context getApplicationContext() { return this; } }
            final class WidgetBoard { static final Executor IO = r -> UpNextWidget.execute.accept(r); }
            public final class UpNextWidget extends Widget {
                public static Consumer<Runnable> execute, posted;
                public static Supplier<Integer> read;
                public static Consumer<Integer> shown;
                static final class Item { int version; }
                Context getContext() { return new Context(); }
                boolean post(Runnable r) { posted.accept(r); return true; }
                private ArrayList<Item> load(Context context) { Item i = new Item(); i.version = read.get(); ArrayList<Item> items = new ArrayList<>(); items.add(i); return items; }
                private void show(ArrayList<Item> items) { shown.accept(items.get(0).version); }
            // ---- cut from UpNextWidget.java ----
            """ + region + "}\n";
    }

    static Class<?> compile(String region) throws Exception {
        Path dir = Files.createTempDirectory("upnext-replay");
        Path src = dir.resolve("UpNextWidget.java");
        Files.writeString(src, replaySource(region));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new NoResult("this Java runtime has no compiler; run with a JDK");
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        if (javac.run(null, null, err, "-nowarn", "-d", dir.toString(), src.toString()) != 0)
            throw new NoResult("the cut-out refresh code does not compile against the fakes:\n" + err + "\nReplay source: " + src);
        return new URLClassLoader(new URL[]{dir.toUri().toURL()}).loadClass("UpNextWidget");
    }

    // ---- exploration ----

    enum Phase { QUEUED, RUNNING, DONE }
    static final class Task {
        final int id; final Runnable body; Phase phase = Phase.QUEUED; int readVersion = -1;
        final Semaphore readDone = new Semaphore(0), release = new Semaphore(0), done = new Semaphore(0);
        volatile Throwable error;
        Task(int id, Runnable body) { this.id = id; this.body = body; }
    }

    /** A reusable stand-in for one WidgetBoard.IO thread. Reusing it keeps thread start-up out of every replay. */
    static final class Worker extends Thread {
        final Semaphore go = new Semaphore(0);
        volatile Task task;
        Worker() { super("Luma widgets"); setDaemon(true); start(); }
        @Override public void run() {
            while (true) {
                go.acquireUninterruptibly();
                Task t = task;
                try { t.body.run(); } catch (Throwable e) { t.error = e; t.readDone.release(); } finally { t.done.release(); }
            }
        }
    }

    /** One replay of one schedule. Only the explorer thread changes this state, except where a worker hands off. */
    static final class Run {
        final Class<?> widgetClass; final Object widget; final Method refresh;
        final List<Task> tasks = new ArrayList<>();
        final ArrayDeque<Runnable> ui = new ArrayDeque<>();
        final List<String> trace = new ArrayList<>();
        final List<Worker> pool;
        int version, refreshesLeft, writesLeft, highestDrawn = -1, lastDrawn = -1, versionAtLastRefresh = -1;
        /** The first break of each property on this schedule, with the steps that led to it. */
        final Map<String, List<String>> broken = new LinkedHashMap<>();
        void breaks(String property, String why) {
            if (broken.containsKey(property)) return;
            List<String> trace = new ArrayList<>(this.trace); trace.add("=> " + why); broken.put(property, trace);
        }

        Run(Class<?> c, List<Worker> pool, int refreshes, int writes) throws Exception {
            widgetClass = c; this.pool = pool; refreshesLeft = refreshes; writesLeft = writes;
            set("execute", (Consumer<Runnable>) this::submit);
            set("posted", (Consumer<Runnable>) r -> { synchronized (ui) { ui.add(r); } });
            set("read", (Supplier<Integer>) () -> {
                Task t = ((Worker) Thread.currentThread()).task;
                t.readVersion = version;
                t.readDone.release();
                t.release.acquireUninterruptibly();
                return t.readVersion;
            });
            set("shown", (Consumer<Integer>) v -> {
                trace.add("    show() draws version " + v);
                if (v < highestDrawn) breaks(NO_ROLLBACK, "drew version " + v + " after version " + highestDrawn);
                highestDrawn = Math.max(highestDrawn, v); lastDrawn = v;
            });
            java.lang.reflect.Constructor<?> k = c.getDeclaredConstructor(); k.setAccessible(true);
            widget = k.newInstance();
            refresh = c.getDeclaredMethod("refresh"); refresh.setAccessible(true);
        }
        void set(String field, Object value) throws Exception { Field f = widgetClass.getField(field); f.set(null, value); }

        void submit(Runnable job) {
            Task task = new Task(tasks.size() + 1, job);
            tasks.add(task);
            trace.add("    WidgetBoard.IO queues task " + task.id);
        }

        Worker idle() {
            for (Worker w : pool) { boolean busy = false; for (Task t : tasks) busy |= t.phase == Phase.RUNNING && w.task == t; if (!busy) return w; }
            throw new IllegalStateException("no idle worker");
        }
        int running() { int n = 0; for (Task t : tasks) if (t.phase == Phase.RUNNING) n++; return n; }
        Task nextQueued() { for (Task t : tasks) if (t.phase == Phase.QUEUED) return t; return null; }

        /** A write changes only the version, and only refresh and start look at it, so a write is placed just before
         *  one of those two. Every other placement gives the same widget states. */
        List<String> enabled() {
            List<String> out = new ArrayList<>();
            if (refreshesLeft > 0) { out.add("refresh"); if (writesLeft > 0) out.add("write refresh"); }
            if (nextQueued() != null && running() < WORKERS) { out.add("start"); if (writesLeft > 0) out.add("write start"); }
            for (Task t : tasks) if (t.phase == Phase.RUNNING) out.add("finish " + t.id);
            if (!ui.isEmpty()) out.add("ui");
            return out;
        }

        void step(String action) throws Exception {
            if (action.startsWith("write ")) {
                writesLeft--; version++;
                trace.add("an app writes Watch Next version " + version);
                action = action.substring(6);
            }
            if (action.equals("refresh")) {
                refreshesLeft--; versionAtLastRefresh = version;
                trace.add("UI thread calls refresh() while Watch Next is at version " + version);
                invoke(refresh);
            } else if (action.equals("start")) {
                Task t = nextQueued(); t.phase = Phase.RUNNING;
                Worker w = idle();
                w.task = t;
                w.go.release();
                await(t.readDone, "task " + t.id + " never called load()");
                if (t.error != null) throw new NoResult("task " + t.id + " threw " + t.error);
                trace.add("IO worker starts task " + t.id + ", load() reads version " + t.readVersion);
            } else if (action.startsWith("finish ")) {
                Task t = tasks.get(Integer.parseInt(action.substring(7)) - 1);
                t.release.release();
                await(t.done, "task " + t.id + " did not return after load()");
                if (t.error != null) throw new NoResult("task " + t.id + " threw " + t.error);
                t.phase = Phase.DONE;
                trace.add("task " + t.id + " returns and posts version " + t.readVersion + " to the UI thread");
            } else {
                Runnable r; synchronized (ui) { r = ui.poll(); }
                trace.add("UI thread runs the next posted runnable");
                r.run();
            }
            int inFlight = 0; for (Task t : tasks) if (t.phase != Phase.DONE) inFlight++;
            if (inFlight > 1) breaks(ONE_READ, inFlight + " reads queued or running at once");
        }
        void invoke(Method m) throws Exception {
            try { m.invoke(widget); } catch (java.lang.reflect.InvocationTargetException e) { throw new NoResult("refresh() threw " + e.getCause()); }
        }
        static void await(Semaphore s, String what) throws Exception {
            if (!s.tryAcquire(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new NoResult(what);
        }
        /** Lets blocked workers end before the next replay swaps the static hooks, so no worker posts into it. */
        void close() throws Exception {
            for (Task t : tasks) if (t.phase == Phase.RUNNING) { t.release.release(); await(t.done, "task " + t.id + " did not end"); }
        }
    }

    static final class Result {
        final Map<String, List<String>> failures = new LinkedHashMap<>();
        long schedules;
        void print() {
            System.out.println("explored " + schedules + " complete schedules");
            for (Map.Entry<String, String> p : PROPERTIES.entrySet()) {
                List<String> trace = failures.get(p.getKey());
                System.out.println((trace == null ? "ok      " : "FAIL    ") + p.getKey() + ": " + p.getValue());
                if (trace != null) for (String line : trace) System.out.println("          " + line);
            }
        }
    }

    /** A refresh arrives after the first read sampled its row. Drawing that row submits two uncached posters. */
    static void checkPosterQueue(Class<?> widget, List<Worker> pool, Result result) throws Exception {
        Run run = new Run(widget, pool, 2, 1);
        try {
            run.step("refresh");
            run.step("start");
            run.step("write refresh");
            run.step("finish 1");

            Runnable poster = () -> {};
            run.set("shown", (Consumer<Integer>) version -> {
                run.trace.add("show() submits two uncached posters for version " + version);
                run.submit(poster);
                run.submit(poster);
            });
            run.step("ui");
            Task next = run.nextQueued();
            if (next == null || next.body == poster) {
                run.breaks(READ_BEFORE_POSTERS, "the pending read does not have the first free worker slot");
                result.failures.put(READ_BEFORE_POSTERS, run.broken.get(READ_BEFORE_POSTERS));
            }
        } finally {
            run.close();
        }
    }

    /** Depth-first over every schedule. Each replay starts from a fresh widget, follows a fixed prefix of choices,
     *  then takes the first choice at every later step until no step is left. Keeps the shortest trace per property. */
    static Result check(String region, int refreshes, int writes) throws Exception {
        Class<?> c = compile(region);
        List<Worker> pool = new ArrayList<>(); for (int i = 0; i < WORKERS; i++) pool.add(new Worker());
        Result result = new Result();
        checkPosterQueue(c, pool, result);
        int[] choice = new int[0], options = new int[0];
        while (true) {
            Run run = new Run(c, pool, refreshes, writes);
            try {
                for (int d = 0; d < choice.length; d++) run.step(run.enabled().get(choice[d]));
                for (List<String> next = run.enabled(); !next.isEmpty(); next = run.enabled()) {
                    choice = Arrays.copyOf(choice, choice.length + 1);
                    options = Arrays.copyOf(options, options.length + 1);
                    options[options.length - 1] = next.size();
                    run.step(next.get(0));
                }
                result.schedules++;
                if (run.lastDrawn < run.versionAtLastRefresh)
                    run.breaks(NEWEST_LANDS, "all work ended with version " + run.lastDrawn + " on screen after a refresh at version " + run.versionAtLastRefresh);
                for (Map.Entry<String, List<String>> b : run.broken.entrySet()) {
                    List<String> old = result.failures.get(b.getKey());
                    if (old == null || b.getValue().size() < old.size()) result.failures.put(b.getKey(), b.getValue());
                }
            } finally { run.close(); }
            int d = choice.length - 1;
            while (d >= 0 && choice[d] + 1 >= options[d]) d--;
            if (d < 0) return result;
            choice = Arrays.copyOf(choice, d + 1); options = Arrays.copyOf(options, d + 1);
            choice[d]++;
        }
    }
}
