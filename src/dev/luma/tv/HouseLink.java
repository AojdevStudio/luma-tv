package dev.luma.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Luma's one path to House Control's HomeService for scenes, owned by the activity rather than the Home widget.
 *  Code 1 reads the home, 2 runs a scene on the real home and 4 on House Control's isolated demo home. HomeService
 *  polls its devices while a client is bound, so the link binds on demand and lets go once it has been idle a while.
 *  Answers arrive on the main thread, and none arrive after close(). */
final class HouseLink {
    static final ComponentName SERVICE = new ComponentName("dev.housecontrol.tv", "dev.housecontrol.tv.HomeService");
    private static final String NO_ANSWER = "House Control did not answer. Open House Control to reconnect.";
    private static final long CONNECT_MS = 6000, IDLE_MS = 10000;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Requests not yet on the worker, oldest first. Each is handed the binder, or null when the connection failed. */
    private final ArrayDeque<Consumer<IBinder>> queue = new ArrayDeque<>();
    private IBinder binder;
    private boolean bound, busy, closed;
    private final Runnable release = this::unbind, timeout = this::giveUp;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) { main.removeCallbacks(timeout); binder = service; next(); }
        public void onServiceDisconnected(ComponentName name) { binder = null; }
        public void onBindingDied(ComponentName name) { giveUp(); }
        public void onNullBinding(ComponentName name) { giveUp(); }
    };

    HouseLink(Context context) { this.context = context.getApplicationContext(); }

    void read(Consumer<HouseScenes.Read> done) { request(HouseLink::fetch, () -> null, HouseScenes.Read.failed(NO_ANSWER), done); }

    /** Sends the scene unless refusal names a reason. refusal runs on the main thread when the worker takes the scene,
     *  after any wait behind other requests, so a mode or plan change during that wait is seen before anything is sent. */
    void run(HouseScenes.Scene scene, Supplier<String> refusal, Consumer<HouseScenes.Outcome> done) {
        request(target -> send(target, scene), () -> {
            String why = refusal.get();
            return why == null ? null : HouseScenes.notRun(why);
        }, HouseScenes.notRun(NO_ANSWER), done);
    }

    void close() {
        closed = true;
        queue.clear();
        unbind();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }

    /** Converts House Control's JSON to the maps, lists and values HouseScenes reads. */
    static HouseScenes.Read read(JSONObject home) {
        try { return HouseScenes.read(tree(home)); } catch (JSONException unreadable) { return HouseScenes.read(null); }
    }

    /** instead runs on the main thread just before call goes to the worker. A value from it answers in place of call. */
    private <T> void request(Function<IBinder, T> call, Supplier<T> instead, T unreachable, Consumer<T> done) {
        if (closed) return;
        main.removeCallbacks(release);
        queue.add(target -> {
            if (target == null) { answer(unreachable, done); return; }
            T refused = instead.get();
            if (refused != null) { answer(refused, done); return; }
            busy = true;
            worker.execute(() -> {
                T value = call.apply(target);
                main.post(() -> { busy = false; answer(value, done); next(); });
            });
        });
        next();
    }

    /** The worker takes one request at a time, so the next one is handed over, and checked, only once it is free. */
    private void next() {
        while (!busy && !queue.isEmpty()) {
            if (binder == null) { connect(); return; }
            queue.poll().accept(binder);
        }
    }

    private void connect() {
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, CONNECT_MS);
        if (bound) return;
        // Android asks for unbindService even when bindService fails, so bound means "asked", and giveUp releases it.
        bound = true;
        boolean found;
        try { found = context.bindService(new Intent().setComponent(SERVICE), connection, Context.BIND_AUTO_CREATE); }
        catch (SecurityException refused) { found = false; }
        if (!found) giveUp();
    }

    private void unbind() {
        main.removeCallbacks(timeout);
        if (bound) try { context.unbindService(connection); } catch (IllegalArgumentException notRegistered) {}
        bound = false;
        binder = null;
    }

    private void giveUp() {
        unbind();
        ArrayList<Consumer<IBinder>> unsent = new ArrayList<>(queue);
        queue.clear();
        for (Consumer<IBinder> request : unsent) request.accept(null);
    }

    private <T> void answer(T value, Consumer<T> done) {
        if (closed) return;
        done.accept(value);
        if (!busy && queue.isEmpty()) { main.removeCallbacks(release); main.postDelayed(release, IDLE_MS); }
    }

    private static HouseScenes.Read fetch(IBinder target) {
        String reply;
        try { reply = transact(target, 1, null); }
        catch (RemoteException lost) { return HouseScenes.Read.failed(NO_ANSWER); }
        catch (RuntimeException refused) { return HouseScenes.Read.failed(refusal(refused)); }
        return HouseScenes.read(parse(reply));
    }

    private static HouseScenes.Outcome send(IBinder target, HouseScenes.Scene scene) {
        String reply;
        try { reply = transact(target, scene.source == HouseScenes.Source.DEMO ? 4 : 2, scene.id); }
        catch (RemoteException lost) { return HouseScenes.unknown(scene); }
        catch (RuntimeException refused) { return HouseScenes.notRun(refusal(refused)); }
        return HouseScenes.outcome(scene, parse(reply));
    }

    /** The reply string. A RuntimeException means House Control refused the call before running anything: it lacks the
     *  code, or HomeService wrote an exception, such as SecurityException for a Luma signed with a key it does not trust. */
    private static String transact(IBinder target, int code, String scene) throws RemoteException {
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            if (scene != null) in.writeString(scene);
            if (!target.transact(code, in, out, 0)) throw new UnsupportedOperationException("Update House Control so Luma can use its scenes.");
            out.readException();
            return out.readString();
        } finally {
            in.recycle();
            out.recycle();
        }
    }

    private static String refusal(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isEmpty() ? "House Control refused Luma's request." : error.getMessage();
    }

    /** null when the reply is missing or is not JSON, which HouseScenes reports as unreadable. */
    private static Object parse(String reply) {
        if (reply == null) return null;
        try { return tree(new JSONTokener(reply).nextValue()); } catch (JSONException unreadable) { return null; }
    }

    private static Object tree(Object json) throws JSONException {
        if (json instanceof JSONObject) {
            JSONObject object = (JSONObject) json;
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                map.put(key, tree(object.get(key)));
            }
            return map;
        }
        if (json instanceof JSONArray) {
            JSONArray array = (JSONArray) json;
            ArrayList<Object> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) list.add(tree(array.get(i)));
            return list;
        }
        return json == JSONObject.NULL ? null : json;
    }
}
