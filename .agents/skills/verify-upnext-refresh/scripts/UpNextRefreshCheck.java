import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   return    load() returns and the worker runs on until it calls post() or ends
 *   post      post() queues its runnable for the UI thread and the worker runs on until its next post() or its end
 *   ui        the UI thread runs the oldest posted runnable (show() records the version it draws)
 * WidgetBoard.IO has two workers, so at most two tasks run at once and tasks start in the order they were queued.
 * Each replay gets a work budget. A replay that has not drained within it gives no result, not a pass.
 *
 * Exit 0: every property held on every schedule. Exit 1: a counterexample. Exit 2: no result.
 */
public final class UpNextRefreshCheck {
    static final String DEFAULT_SOURCE = "src/dev/luma/tv/UpNextWidget.java";
    static final int WORKERS = 2;
    static final long STEP_TIMEOUT_SECONDS = 10;
    /** One refresh request needs 5 steps today: refresh, start, return, post, ui. Twice that leaves room for one more
     *  job per request. A replay that needs more has not drained, for example because a rerun requests itself forever. */
    static final int STEPS_PER_REFRESH = 10;
    /** The static fields replaySource() adds so the fakes can call back into the explorer. They are not widget state. */
    static final Set<String> HOOKS = Set.of("execute", "posted", "read", "shown");

    static final String ONE_READ = "one_read_at_a_time";
    static final String NO_ROLLBACK = "never_shows_older";
    static final String NEWEST_LANDS = "newest_request_lands";
    static final String READ_BEFORE_POSTERS = "pending_read_before_posters";
    static final Map<String, String> PROPERTIES = new LinkedHashMap<>();
    static {
        PROPERTIES.put(READ_BEFORE_POSTERS, "In the two-poster replay, the pending provider read is queued before posters from the previous draw.");
        PROPERTIES.put(ONE_READ, "At most one Up Next read is queued or running, so reads never share lastUsed or race each other.");
        PROPERTIES.put(NO_ROLLBACK, "Up Next never draws a Watch Next version older than one it already drew.");
        PROPERTIES.put(NEWEST_LANDS, "Whenever all work has drained, Up Next shows data at least as new as the data at the last refresh request.");
    }
    /** What the livelock mutant must produce instead of a property break. */
    static final String WORK_BUDGET = "no result from the work budget";

    /** A scratch change to the cut-out refresh code that must break the named property. Edits are find, replace pairs,
     *  applied in order, and each find must occur exactly once in the text it edits. */
    record Mutant(String name, String expect, String... edits) {}
    static final List<Mutant> MUTANTS = List.of(
        new Mutant("drop the in-flight guard", ONE_READ, "if(loading){stale=true;return;}", ""),
        new Mutant("drop the in-flight guard", NO_ROLLBACK, "if(loading){stale=true;return;}", ""),
        new Mutant("drop the rerun after a stale request", NEWEST_LANDS, "if(stale){stale=false;refresh();}", ""),
        new Mutant("clear the flag before the read", ONE_READ, "ArrayList<Item> items=load(app);", "loading=false;ArrayList<Item> items=load(app);"),
        new Mutant("clear the flag on the worker before post()", NO_ROLLBACK,
            "loading=false;", "", "ArrayList<Item> items=load(app);", "ArrayList<Item> items=load(app);loading=false;"),
        new Mutant("ignore the first refresh", NEWEST_LANDS,
            "@Override void refresh(){", "private boolean seen;@Override void refresh(){if(!seen){seen=true;return;}"),
        new Mutant("keep stale set after the rerun", WORK_BUDGET, "if(stale){stale=false;refresh();}", "if(stale){refresh();}"));

    static class NoResult extends Exception { NoResult(String m) { super(m); } }
    /** A replay that did not drain within its work budget. */
    static final class OverBudget extends NoResult { OverBudget(String m) { super(m); } }

    public static void main(String[] args) {
        try {
            String source = DEFAULT_SOURCE;
            boolean selfCheck = false;
            int refreshes = 3, writes = 2;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--source" -> source = value(args, ++i, "--source");
                    case "--self-check" -> selfCheck = true;
                    case "--refreshes" -> refreshes = count(args, ++i, "--refreshes", 1);
                    case "--writes" -> writes = count(args, ++i, "--writes", 0);
                    case "--help" -> { System.out.println("usage: java UpNextRefreshCheck.java [--source UpNextWidget.java] [--refreshes 3] [--writes 2] [--self-check]"); return; }
                    default -> throw new NoResult("unknown argument " + args[i]);
                }
            }
            String region = cutRefreshRegion(Files.readString(Paths.get(source)));
            System.out.println("source  " + source);
            System.out.println("region  sha256 " + sha256(region) + " (constructor end to load())");
            System.out.println("bound   " + refreshes + " refresh requests, " + writes + " Watch Next writes, " + WORKERS + " IO workers, "
                + refreshes * STEPS_PER_REFRESH + " steps per replay");
            Result result = check(region, refreshes, writes);
            result.print();
            if (selfCheck) {
                boolean caught = true;
                Map<String, Result> checked = new java.util.HashMap<>(); // mutants that share an edit share one exploration
                for (Mutant m : MUTANTS) {
                    String mutated = region;
                    for (int e = 0; e < m.edits.length; e += 2) {
                        int at = mutated.indexOf(m.edits[e]);
                        if (at < 0 || mutated.indexOf(m.edits[e], at + 1) >= 0)
                            throw new NoResult("self-check anchor for mutant '" + m.name + "' must occur once: " + m.edits[e]);
                        mutated = mutated.replace(m.edits[e], m.edits[e + 1]);
                    }
                    boolean hit;
                    if (m.expect.equals(WORK_BUDGET)) {
                        try { check(mutated, refreshes, writes); hit = false; } catch (OverBudget e) { hit = true; }
                    } else {
                        Result r = checked.get(mutated);
                        if (r == null) { r = check(mutated, refreshes, writes); checked.put(mutated, r); }
                        hit = r.failures.containsKey(m.expect);
                    }
                    caught &= hit;
                    System.out.println((hit ? "caught  " : "MISSED  ") + "mutant '" + m.name + "' gives " + m.expect);
                }
                if (!caught) throw new NoResult("self-check: a mutant was not caught, so this check cannot be trusted");
            }
            if (!result.failures.isEmpty()) { System.out.println("FAIL: counterexample above"); System.exit(1); }
            if (!result.unknown.isEmpty()) throw new NoResult("a directed replay could not reach its scenario, see UNKNOWN above");
            System.out.println("PASS within the bound above");
            System.exit(0);
        } catch (NoResult e) {
            System.out.println("no result: " + e.getMessage());
            System.exit(2);
        } catch (Throwable e) {
            System.out.println("no result: the checker itself failed");
            e.printStackTrace(System.out);
            System.exit(2);
        }
    }

    static String value(String[] args, int i, String flag) throws NoResult {
        if (i >= args.length) throw new NoResult(flag + " needs a value");
        return args[i];
    }

    static int count(String[] args, int i, String flag, int min) throws NoResult {
        String v = value(args, i, flag);
        try {
            int n = Integer.parseInt(v);
            if (n >= min) return n;
        } catch (NumberFormatException e) { /* reported below with the bound */ }
        throw new NoResult(flag + " must be a whole number of at least " + min + ", got '" + v + "'");
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

    /** Where a task is. A worker pauses inside load() (LOADING) and at the entry of each post() (POSTING), so the UI
     *  thread can act between the read, any worker code after it, and the moment the result is queued. */
    enum Phase { QUEUED, LOADING, POSTING, DONE }
    static final class Task {
        final int id; final Runnable body; Phase phase = Phase.QUEUED; int readVersion = -1;
        /** The explorer releases resume to let the worker go on. The worker sets at and releases yielded when it pauses or ends. */
        final Semaphore resume = new Semaphore(0), yielded = new Semaphore(0);
        volatile Phase at = Phase.QUEUED;
        volatile Throwable error;
        Task(int id, Runnable body) { this.id = id; this.body = body; }
        void pause(Phase where) { at = where; yielded.release(); resume.acquireUninterruptibly(); }
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
                try { t.body.run(); } catch (Throwable e) { t.error = e; } finally { t.at = Phase.DONE; t.yielded.release(); }
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
        final int budget;
        int steps, version, refreshesLeft, writesLeft, highestDrawn = -1, lastDrawn = -1, versionAtLastRefresh = -1;
        /** The first break of each property on this schedule, with the steps that led to it. */
        final Map<String, List<String>> broken = new LinkedHashMap<>();
        void breaks(String property, String why) {
            if (broken.containsKey(property)) return;
            List<String> trace = new ArrayList<>(this.trace); trace.add("=> " + why); broken.put(property, trace);
        }

        Run(Class<?> c, List<Worker> pool, int refreshes, int writes) throws Exception {
            widgetClass = c; this.pool = pool; refreshesLeft = refreshes; writesLeft = writes;
            budget = refreshes * STEPS_PER_REFRESH;
            set("execute", (Consumer<Runnable>) this::submit);
            set("posted", (Consumer<Runnable>) r -> {
                if (Thread.currentThread() instanceof Worker w) w.task.pause(Phase.POSTING);
                synchronized (ui) { ui.add(r); }
            });
            set("read", (Supplier<Integer>) () -> {
                Task t = ((Worker) Thread.currentThread()).task;
                t.readVersion = version;
                t.pause(Phase.LOADING);
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

        boolean onWorker(Task t) { return t.phase == Phase.LOADING || t.phase == Phase.POSTING; }
        Worker idle() {
            for (Worker w : pool) { boolean busy = false; for (Task t : tasks) busy |= onWorker(t) && w.task == t; if (!busy) return w; }
            throw new IllegalStateException("no idle worker");
        }
        int running() { int n = 0; for (Task t : tasks) if (onWorker(t)) n++; return n; }
        Task nextQueued() { for (Task t : tasks) if (t.phase == Phase.QUEUED) return t; return null; }
        boolean drained() { for (Task t : tasks) if (t.phase != Phase.DONE) return false; return ui.isEmpty(); }

        /** A write changes only the version, and only refresh and start look at it, so a write is placed just before
         *  one of those two. Every other placement gives the same widget states. resumeTo() makes sure load() is
         *  entered only on a start step, so this stays true. */
        List<String> enabled() {
            List<String> out = new ArrayList<>();
            if (refreshesLeft > 0) { out.add("refresh"); if (writesLeft > 0) out.add("write refresh"); }
            if (nextQueued() != null && running() < WORKERS) { out.add("start"); if (writesLeft > 0) out.add("write start"); }
            for (Task t : tasks) {
                if (t.phase == Phase.LOADING) out.add("return " + t.id);
                if (t.phase == Phase.POSTING) out.add("post " + t.id);
            }
            if (!ui.isEmpty()) out.add("ui");
            return out;
        }

        void step(String action) throws Exception {
            if (steps == budget)
                throw new OverBudget("the replay did not drain within " + budget + " steps (" + STEPS_PER_REFRESH
                    + " per refresh request), so work may never end. Steps so far:\n          " + String.join("\n          ", trace));
            steps++;
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
                Task t = nextQueued();
                Worker w = idle();
                w.task = t;
                w.go.release();
                resumeTo(t, true);
            } else if (action.startsWith("return ") || action.startsWith("post ")) {
                Task t = tasks.get(Integer.parseInt(action.substring(action.indexOf(' ') + 1)) - 1);
                boolean returning = t.phase == Phase.LOADING;
                String before = returning ? state() : null;
                advance(t);
                // A return that changes no state commutes with every other step, so moving it to just before its
                // post() loses no order. Only a worker that changes state before post() needs post() as its own step.
                if (returning && t.phase == Phase.POSTING && state().equals(before)) {
                    trace.add("    the worker changed no state before post(), so post() goes on in the same step");
                    advance(t);
                }
            } else {
                Runnable r; synchronized (ui) { r = ui.poll(); }
                trace.add("UI thread runs the next posted runnable");
                try { r.run(); } catch (Throwable e) { throw new NoResult("a posted runnable threw " + e); }
            }
            int reads = 0; for (Task t : tasks) if (t.phase == Phase.QUEUED || t.phase == Phase.LOADING) reads++;
            if (reads > 1) breaks(ONE_READ, reads + " reads queued or running at once");
            if (drained() && lastDrawn < versionAtLastRefresh)
                breaks(NEWEST_LANDS, "all work drained with version " + lastDrawn + " on screen after a refresh at version " + versionAtLastRefresh);
        }

        void advance(Task t) throws Exception {
            if (t.phase == Phase.POSTING) trace.add("task " + t.id + " post() queues its runnable for the UI thread");
            t.resume.release();
            resumeTo(t, false);
        }

        /** Everything a worker step could change that another step can see: the widget's own fields, followed into
         *  collections, arrays and objects of the replay classes, plus the queues and what show() drew. */
        String state() throws Exception {
            StringBuilder out = new StringBuilder(tasks.size() + " " + ui.size() + " " + highestDrawn + " " + lastDrawn);
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            for (Field f : widgetClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && HOOKS.contains(f.getName())) continue;
                f.setAccessible(true);
                out.append(' ').append(f.getName()).append('=');
                describe(f.get(Modifier.isStatic(f.getModifiers()) ? null : widget), out, seen);
            }
            return out.toString();
        }
        void describe(Object o, StringBuilder out, IdentityHashMap<Object, Boolean> seen) throws Exception {
            if (o == null || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof String || o instanceof Enum<?>) { out.append(o); return; }
            if (seen.put(o, true) != null) { out.append('@').append(System.identityHashCode(o)); return; }
            out.append('{');
            if (o.getClass().isArray()) for (int i = 0; i < Array.getLength(o); i++) { describe(Array.get(o, i), out, seen); out.append(','); }
            else if (o instanceof Map<?, ?> m) for (Map.Entry<?, ?> e : m.entrySet()) { describe(e.getKey(), out, seen); out.append(':'); describe(e.getValue(), out, seen); out.append(','); }
            else if (o instanceof Iterable<?> it) for (Object e : it) { describe(e, out, seen); out.append(','); }
            else if (o.getClass().getClassLoader() == widgetClass.getClassLoader())
                for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        out.append(f.getName()).append('='); describe(f.get(o), out, seen); out.append(',');
                    }
            else out.append(o.getClass().getName()).append('@').append(System.identityHashCode(o));
            out.append('}');
        }

        void invoke(Method m) throws Exception {
            try { m.invoke(widget); } catch (java.lang.reflect.InvocationTargetException e) { throw new NoResult("refresh() threw " + e.getCause()); }
        }
        /** Waits for the worker to pause or end, then records where it stopped. */
        void resumeTo(Task t, boolean fromStart) throws Exception {
            if (!t.yielded.tryAcquire(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new NoResult("task " + t.id + " did not pause or end");
            if (t.error != null) throw new NoResult("task " + t.id + " threw " + t.error);
            Phase was = t.phase;
            t.phase = t.at;
            String who = was == Phase.QUEUED ? "IO worker starts task " + t.id + " and"
                : was == Phase.LOADING ? "task " + t.id + " load() returns version " + t.readVersion + ", and the worker"
                : "task " + t.id + " worker";
            switch (t.phase) {
                case LOADING -> {
                    if (!fromStart) throw new NoResult("task " + t.id + " called load() again after it returned, so a write placed only before refresh or start would miss orders");
                    trace.add("IO worker starts task " + t.id + ", load() reads version " + t.readVersion);
                }
                case POSTING -> trace.add(who + " reaches post()");
                default -> trace.add(who + " ends");
            }
        }

        /** Lets paused workers end before the next replay swaps the static hooks, so no worker posts into it. */
        void close() throws Exception {
            for (Task t : tasks)
                for (int i = 0; onWorker(t); i++) {
                    if (i == 100) throw new NoResult("task " + t.id + " did not end");
                    t.resume.release();
                    if (!t.yielded.tryAcquire(STEP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new NoResult("task " + t.id + " did not end");
                    t.phase = t.at;
                }
        }
    }

    static final class Result {
        final Map<String, List<String>> failures = new LinkedHashMap<>();
        final Map<String, String> unknown = new LinkedHashMap<>();
        long schedules; int longest;
        void print() {
            System.out.println("explored " + schedules + " complete schedules, the longest " + longest + " steps");
            for (Map.Entry<String, String> p : PROPERTIES.entrySet()) {
                List<String> trace = failures.get(p.getKey());
                String why = unknown.get(p.getKey());
                System.out.println((trace != null ? "FAIL    " : why != null ? "UNKNOWN " : "ok      ") + p.getKey() + ": " + p.getValue());
                if (trace != null) for (String line : trace) System.out.println("          " + line);
                if (why != null) System.out.println("          " + why);
            }
        }
    }

    /** A refresh arrives after the first read sampled its row. Drawing that row submits two uncached posters. */
    static void checkPosterQueue(Class<?> widget, List<Worker> pool, Result result) throws Exception {
        Run run = new Run(widget, pool, 2, 1);
        try {
            for (String step : List.of("refresh", "start", "write refresh", "return 1", "post 1")) {
                if (step.equals("post 1") && run.tasks.get(0).phase == Phase.DONE) continue; // return 1 already posted
                if (!run.enabled().contains(step)) {
                    result.unknown.put(READ_BEFORE_POSTERS, "the replay could not take step '" + step + "' after: " + String.join(" / ", run.trace));
                    return;
                }
                run.step(step);
            }

            Runnable poster = () -> {};
            run.set("shown", (Consumer<Integer>) version -> {
                run.trace.add("show() submits two uncached posters for version " + version);
                run.submit(poster);
                run.submit(poster);
            });
            run.step("ui");
            boolean drew = false; for (Task t : run.tasks) drew |= t.body == poster;
            if (!drew) {
                result.unknown.put(READ_BEFORE_POSTERS, "the first result was never drawn, so no poster was queued: " + String.join(" / ", run.trace));
                return;
            }
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
                result.longest = Math.max(result.longest, run.steps);
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
