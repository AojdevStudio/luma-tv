import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class FailedReadTest {
    private enum RefreshTime { BEFORE_READ, BEFORE_DELIVERY, AFTER_DELIVERY }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(UpNextRefreshCheck.DEFAULT_SOURCE));
        Class<?> widgetClass = UpNextRefreshCheck.compile(UpNextRefreshCheck.cutRefreshRegion(source));
        for (RefreshTime time : RefreshTime.values()) checkRecovery(widgetClass, time);
    }

    private static void checkRecovery(Class<?> widgetClass, RefreshTime time) throws Exception {
        var io = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var drawn = new ArrayList<Integer>();
        widgetClass.getField("execute").set(null, (Consumer<Runnable>) io::addLast);
        widgetClass.getField("posted").set(null, (Consumer<Runnable>) ui::addLast);
        widgetClass.getField("shown").set(null, (Consumer<Integer>) drawn::add);
        var widget = widgetClass.getDeclaredConstructor().newInstance();
        var refresh = widgetClass.getDeclaredMethod("refresh");
        refresh.setAccessible(true);

        var read = widgetClass.getField("read");
        read.set(null, (Supplier<Integer>) () -> 1);
        refresh.invoke(widget);
        io.remove().run();
        ui.remove().run();

        read.set(null, (Supplier<Integer>) () -> { throw new SecurityException("read refused"); });
        refresh.invoke(widget);
        if (time == RefreshTime.BEFORE_READ) refresh.invoke(widget);
        io.remove().run();
        if (time == RefreshTime.BEFORE_DELIVERY) refresh.invoke(widget);
        ui.remove().run();
        if (!drawn.equals(List.of(1))) throw new AssertionError("Failed read replaced the previous rows");

        read.set(null, (Supplier<Integer>) () -> 2);
        if (time == RefreshTime.AFTER_DELIVERY) {
            if (!io.isEmpty()) throw new AssertionError("Retried without another request");
            refresh.invoke(widget);
        }
        if (io.size() != 1) throw new AssertionError("Expected one recovery read, got " + io.size());
        io.remove().run();
        ui.remove().run();
        if (!drawn.equals(List.of(1, 2)) || !io.isEmpty() || !ui.isEmpty())
            throw new AssertionError("Expected one fresh draw after recovery, saw " + drawn);
        System.out.println("Passed failed read with refresh at " + time);
    }
}
