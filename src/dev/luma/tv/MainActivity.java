package dev.luma.tv;

import android.app.*;
import android.animation.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** An offline, remote-first home screen. No network, analytics, or accessibility service. */
public final class MainActivity extends Activity {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    /** Health checks run here, never on the main thread: package and storage queries can block. */
    private final ExecutorService healthReads = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<App> apps = new ArrayList<>();
    private final ArrayList<View> tabs = new ArrayList<>();
    private final ArrayList<Tile> tiles = new ArrayList<>();
    private SharedPreferences prefs;
    private HomePresets presets;
    private Profiles profiles;
    private SonicFeedback sonic;
    private HouseWidgets houseWidgets;
    private FrameLayout root, content, hero;
    private final ArrayList<Animator> pageAnimations=new ArrayList<>();
    private View sceneryShade;
    private Wallpaper wallpaper;
    private TextView heroTitle, heroDescription, clock;
    private String heroKey="";
    private boolean holdGreeting;
    private ScrollView scroll;
    private LinearLayout rows;
    private String page = "home", selectedPackage = "com.google.android.youtube.tv";
    private String homeReturnPackage = "com.google.android.youtube.tv";
    private HouseScenes.Read houseRead;
    private HouseLink houseLink;
    private TextView brand;
    /** The wallpaper is pale (Silver by day): text and glyphs turn dark and glass turns white. */
    private boolean light;
    private Daylight daylight;
    private final Runnable daylightTick=this::syncDaylight;
    private FrameLayout tabBar;
    private TextView modeChip;
    /** House Control TV is installed and enabled: only then does Home show the scenes and the 3D home. */
    private boolean house;
    private int capabilitiesRow;
    private CapabilityCenter capabilityOverlay;
    /** Home pages of widgets chosen in Settings (WidgetBoard); homePage is the page shown, kept across rebuilds. */
    private WidgetBoard board;
    private int homePage;
    /** Installed apps by package, replaced as a whole on each discovery: widgets read it from their own threads. */
    private volatile Map<String,App> appIndex=Collections.emptyMap();
    private static final String HOUSE_CONTROL="dev.housecontrol.tv";
    private final BroadcastReceiver packages=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            Uri data=intent.getData();if(data==null||!HOUSE_CONTROL.equals(data.getSchemeSpecificPart()))return;
            // An update removes and re-adds the package; only the final state matters.
            if(Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())&&intent.getBooleanExtra(Intent.EXTRA_REPLACING,false))return;
            syncHouse();
        }
    };
    private boolean loaded, loading, started, homeGreeting=true;
    private float unit = 1f;
    private int lastDirectionX=1, lastDirectionY=0;
    private String heroPackage="";
    private final android.view.animation.Interpolator ease = new PathInterpolator(.22f,1f,.36f,1f);
    private final android.view.animation.Interpolator drift = new PathInterpolator(.2f,.72f,.25f,1f);
    private final android.view.animation.Interpolator spring = t -> {
        if(t>=1)return 1;
        double time=t*.42,frequency=25,damping=.72;
        double damped=frequency*Math.sqrt(1-damping*damping);
        return (float)(1-Math.exp(-damping*frequency*time)*(Math.cos(damped*time)+damping*frequency/damped*Math.sin(damped*time)));
    };
    private final Runnable clockTick = new Runnable() {
        public void run() {
            if (!started) return;
            if (clock != null) clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            handler.postDelayed(this, 15000);
        }
    };
    private static final String[] FIRST = {"com.google.android.youtube.tv", "com.plexapp.android", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.spotify.tv.android", "com.google.android.youtube.tvmusic"};
    private static final String[] DEFAULT_HIDDEN = {"tv.pluto.android", "com.tcl.videoplayer", "com.google.android.play.games", "com.tcl.waterfall.overseas", "com.haystack.android"};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("luma", MODE_PRIVATE);
        HomePresets savedPresets=HomePresets.parse(prefs.getString(HomePresets.KEY,null));presets=savedPresets==null?HomePresets.EMPTY:savedPresets;
        Profiles savedProfile=Profiles.parse(prefs.getString(Profiles.KEY,null));profiles=savedProfile==null?Profiles.DEFAULT:savedProfile;
        hideDefaultApps();
        unit = getResources().getDisplayMetrics().widthPixels / 960f;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        sonic=new SonicFeedback(this);
        daylight=new Daylight(this);
        // 2.2 introduces Silver, light by day and dark after sunset, as the default wallpaper.
        if(!prefs.getBoolean("silver_default",false))prefs.edit().putInt("wallpaper",SILVER).putBoolean("silver_default",true).apply();
        light=pale(variant(wallpaperStyle()));
        house=houseInstalled();
        houseLink=new HouseLink(this);
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_PACKAGE_ADDED);filter.addAction(Intent.ACTION_PACKAGE_REMOVED);filter.addAction(Intent.ACTION_PACKAGE_CHANGED);filter.addDataScheme("package");
        registerReceiver(packages,filter);
        createShell();
        applyWeatherLocation(getIntent());
        discover();
    }

    @Override protected void onResume() {
        super.onResume();
        started = true;
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        if(loaded && page.equals("home")){selectedPackage="com.google.android.youtube.tv";showGreeting();root.post(()->{holdGreeting=true;focusFirst();});}
        if (loaded) discover();
        syncHouse();
        // A sunrise or sunset that passed while an app was open switches a moment after Home returns, once that
        // app has given back its GPU memory: switching at once could fail to allocate (EGL_BAD_ALLOC) on this stick.
        handler.removeCallbacks(daylightTick);handler.postDelayed(daylightTick,900);
        wallpaper.syncAmbient();
        if(houseWidgets!=null)houseWidgets.setActive(board==null||board.onHouse());
        if(board!=null){board.setActive(true);board.refresh();}
    }
    @Override protected void onPause() {
        super.onPause(); if(houseWidgets!=null)houseWidgets.setActive(false); if(board!=null)board.setActive(false); started = false; handler.removeCallbacks(clockTick); handler.removeCallbacks(daylightTick);
        content.animate().cancel();content.setAlpha(1);
        for(Tile tile:tiles) tile.movement.snap();
        wallpaper.pauseAmbient();wallpaper.rest();
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused);if(wallpaper!=null)wallpaper.syncAmbient(); }
    @Override protected void onDestroy() { try{unregisterReceiver(packages);}catch(IllegalArgumentException ignored){} if(wallpaper!=null){wallpaper.pauseAmbient();wallpaper.surfaceThread.quitSafely();}super.onDestroy(); handler.removeCallbacksAndMessages(null); loader.shutdownNow(); healthReads.shutdownNow(); houseLink.close(); if(sonic!=null)sonic.release(); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String look=intent.getStringExtra("luma.daylight");
        if(look!=null&&look.matches("day|night|auto")){daylightOverride=look;if(started)syncDaylight();}
        String demo=intent.getStringExtra("luma.demo");if(demo!=null){Promo.on=demo.equals("promo");if(Promo.on)Promo.start();board=null;homePage=0;showPage("home",true);} // board=null: start on the first page
        String inputs=intent.getStringExtra("luma.inputs");if(inputs!=null){InputsWidget.demo=inputs.equals("demo");showPage(page,true);} // arrives while paused
        applyWeatherLocation(intent);
        if (intent.hasCategory(Intent.CATEGORY_HOME)){selectedPackage="com.google.android.youtube.tv";if(started)homePage=0;showPage("home", true);} // Home pressed on Home: back to the first page
    }
    private void applyWeatherLocation(Intent intent){String weatherLocation=intent.getStringExtra("luma.weather.location");if(weatherLocation!=null){String[] fields=weatherLocation.split("\\|",-1);if(fields.length==3)setWeatherLocation(fields[0],fields[1],fields[2]);else resetInvalidWeatherLocation();}}
    private void setWeatherLocation(String city,String lat,String lon){WeatherLocation location=WeatherLocation.parse(city,lat,lon);if(location==null){resetInvalidWeatherLocation();return;}prefs.edit().putString("weather_city",location.city).putString("weather_lat",Double.toString(location.lat)).putString("weather_lon",Double.toString(location.lon)).remove("weather_json").remove("weather_key").apply();showPage(page,true);}
    private void resetInvalidWeatherLocation(){Toast.makeText(this,"Weather location reset to Automatic.",Toast.LENGTH_SHORT).show();clearWeatherLocation();}
    private void clearWeatherLocation(){prefs.edit().remove("weather_city").remove("weather_lat").remove("weather_lon").remove("weather_json").remove("weather_key").apply();showPage(page,true);}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(board!=null)board.refresh();
    }

    @Override public void dump(String prefix,FileDescriptor fd,PrintWriter writer,String[] args) {
        super.dump(prefix,fd,writer,args);
        if(wallpaper!=null)writer.println(prefix+"Luma ambient: running="+wallpaper.ambientRunning+" ticks="+wallpaper.ambientTicks+" seconds="+wallpaper.ambientSeconds+" style="+wallpaper.requestedStyle+" page="+page);
        if(daylight!=null){long now=System.currentTimeMillis();long[] sun=daylight.times(now);SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);
            writer.println(prefix+"Luma daylight: zone="+TimeZone.getDefault().getID()+" sunrise="+f.format(new Date(sun[0]))+" sunset="+f.format(new Date(sun[1]))+" day="+daylight.isDay(now)+" next="+f.format(new Date(daylight.nextChange(now)))+" light="+light);}
        writer.println(prefix+"Luma capabilities: clock="+prefs.getBoolean("clock",true)+" last="+prefs.getString(Capabilities.LAST,"") );
        writer.println(prefix+"Luma home: mode="+profiles.mode.id+" layout="+presets.active.name()+" saved="+(presets.choices().size()-HomePresets.BuiltIn.values().length-1));
        HouseScenes.Read read=house?houseRead:null;
        writer.println(prefix+"Luma scenes: house="+house+" read="+(read==null?"none":read.home==null?"failed":read.home.source.name().toLowerCase(Locale.ROOT))
            +" scenes="+(read==null||read.home==null?0:read.home.scenes.size())+" ready="+(read!=null&&read.home!=null&&read.home.blocked==null));
    }
    private boolean houseInstalled(){try{return getPackageManager().getApplicationInfo(HOUSE_CONTROL,0).enabled;}catch(PackageManager.NameNotFoundException e){return false;}}
    /** Rebuilds Home when House Control TV is installed, removed, switched on or off. */
    private void syncHouse(){
        boolean now=houseInstalled();if(now==house)return;house=now;
        if(!now)houseRead=null;
        if(page.equals("home"))showPage("home",started);
    }
    private int px(float value) { return Math.round(value * unit); }
    private boolean motion() { return prefs.getBoolean("motion", true) && ValueAnimator.areAnimatorsEnabled(); }
    private void sound() { if (prefs.getBoolean("sound", true)) sonic.open(); }
    private void navigationSound(int index) { if(prefs.getBoolean("sound",true))sonic.step((index%6)/5f,lastDirectionX+lastDirectionY); }
    private TextView text(String value, float size, int color, String family) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextColor(color);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(size));
        view.setTypeface(Typeface.create(family, Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }
    private FrameLayout.LayoutParams pos(float w, float h, float x, float y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w < 0 ? (int)w : px(w), h < 0 ? (int)h : px(h));
        lp.leftMargin = px(x); lp.topMargin = px(y); return lp;
    }
    private GradientDrawable shape(int color, float radius) {
        GradientDrawable gd = new GradientDrawable(); gd.setColor(color); gd.setCornerRadius(px(radius)); return gd;
    }
    /** Text and glyph colour with the given alpha: near-black on a pale wallpaper, white elsewhere. */
    private int ink(int alpha){return (alpha<<24)|(light?0x1D1D1F:0xFFFFFF);}
    /** Unfocused glass: frosted white on a pale wallpaper, a faint white veil on dark ones. */
    private int glass(){return light?0x8CFFFFFF:0x1FFFFFFF;}
    private void shadow(TextView t,float radius){if(light)t.setShadowLayer(0,0,0,0);else t.setShadowLayer(px(radius),0,px(1),0x55000000);}
    private int wallpaperStyle(){return Math.max(0,Math.min(WALLPAPERS.length-1,layout().wallpaper));}
    private HomePresets.Layout owner(){
        ArrayList<String> homeApps=new ArrayList<>();for(App app:visibleApps(false))homeApps.add(app.pkg);
        return new HomePresets.Layout(prefs.getInt("wallpaper",SILVER),WidgetBoard.enabled(prefs),homeApps,Collections.<String>emptyList());
    }
    private HomePresets.Layout layout(){return presets.current(owner());}
    private List<String> homeApps(){
        ArrayList<String> shown=new ArrayList<>();
        for(String pkg:profiles.home(layout().apps))if(appIndex.containsKey(pkg)){shown.add(pkg);if(shown.size()==6)break;}
        return shown;
    }
    private boolean allowsApp(String pkg){return profiles.allowsApp(pkg,homeApps().contains(pkg));}
    private String homeLayout(){return presets.active.equals(HomePresets.Selection.DEFAULT)?null:presets.active.name();}
    private String lockedLine(){return "Home shows the "+homeLayout()+" layout. To change your own Home, choose Default in Settings, Capabilities, Layouts.";}
    /** Silver shows its light picture between sunrise and sunset and its dark one at night. */
    private int variant(int style){return style==SILVER&&!isDay()?SILVER_NIGHT:style;}
    /** QA: am start -n dev.luma.tv/.MainActivity --es luma.daylight day|night|auto previews either look (not saved). */
    private String daylightOverride="auto";
    private boolean isDay(){return daylightOverride.equals("auto")?daylight.isDay(System.currentTimeMillis()):daylightOverride.equals("day");}
    private static boolean pale(int variant){return variant==SILVER;}
    /** Follows sunrise and sunset: switches Silver's picture and, with it, the colours of the page. */
    private void syncDaylight(){
        handler.removeCallbacks(daylightTick);
        if(!started)return;
        long now=System.currentTimeMillis();
        handler.postDelayed(daylightTick,Math.max(30000,Math.min(3600000,daylight.nextChange(now)-now+2000)));
        if(overlay!=null)return; // the wallpaper picker previews freely; the theme follows once it closes
        wallpaper.update();
        boolean next=pale(variant(wallpaperStyle()));
        if(next==light)return;
        if(!motion()){showPage(page,true);return;}
        content.animate().cancel();
        // Not while another app starts: a new page (and its WebView) then competes with it for GPU memory, which
        // ended in EGL_BAD_ALLOC on this stick once. Paused, the colours are left as they are; onResume switches them.
        content.animate().alpha(0).setDuration(220).setInterpolator(ease).withEndAction(()->{if(!started){content.setAlpha(1);return;}showPage(page,true);content.setAlpha(0);content.animate().alpha(1).setDuration(420).setInterpolator(ease).start();}).start();
    }
    private void paintChrome(){
        if(tabBar!=null)tabBar.setBackground(shape(light?0x47FFFFFF:0x1FFFFFFF,22));
        if(clock!=null){clock.setTextColor(ink(0xF2));shadow(clock,6);}
        if(sceneryShade!=null)sceneryShade.setBackgroundColor(light?0x73F2F2F5:0x80000000);
        tabs.get(2).setVisibility(profiles.allows(Profiles.Content.SETTINGS)?View.VISIBLE:View.GONE);
        paintMode();
    }
    private void paintMode(){
        if(modeChip==null)return;
        boolean f=modeChip.hasFocus();String label=profiles.mode.name+" mode";
        modeChip.setText(label);modeChip.setContentDescription(label+". OK changes the mode.");
        GradientDrawable ring=shape(f?(light?0xFF1D1D1F:0xF5FFFFFF):Color.TRANSPARENT,18);if(!f)ring.setStroke(Math.max(1,px(1)),ink(0x66));
        modeChip.setBackground(ring);modeChip.setTextColor(f?(light?Color.WHITE:0xFF17202B):ink(0xFF));
    }
    private void focusPage(){
        View widget=page.equals("home")&&board!=null?board.first():null;
        if(widget!=null)widget.requestFocus();else if(page.equals("settings"))controlsFocus(0);else focusFirst();
    }
    private void modeCenter(){
        CapabilityCenter center=new CapabilityCenter(CapabilityPanel.Section.MODE,false,null);
        root.post(()->{if(capabilityOverlay==center&&!center.rows.isEmpty())center.rows.get(0).requestFocus();});
    }

    private void createShell() {
        root = new FrameLayout(this); root.setClipChildren(false); root.setClipToPadding(false);
        wallpaper = new Wallpaper(); root.addView(wallpaper, new FrameLayout.LayoutParams(-1, -1));
        sceneryShade=new View(this);sceneryShade.setBackgroundColor(0x80000000);sceneryShade.setAlpha(0);
        root.addView(sceneryShade,new FrameLayout.LayoutParams(-1,-1));
        content = new FrameLayout(this); content.setClipChildren(false); root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        // No wordmark: the page opens with the greeting, as on tvOS.
        brand = text("luma", 23, Color.WHITE, "sans-serif-medium"); brand.setVisibility(View.GONE);
        root.addView(brand, pos(120, 32, 48, 29));
        LinearLayout navigation = new LinearLayout(this); navigation.setGravity(Gravity.CENTER);
        modeChip = text("", 14, Color.WHITE, "sans-serif-medium"); modeChip.setId(View.generateViewId());
        modeChip.setPadding(px(14), 0, px(14), 0); modeChip.setGravity(Gravity.CENTER);
        modeChip.setFocusable(true); modeChip.setFocusableInTouchMode(true); modeChip.setSoundEffectsEnabled(false);
        modeChip.setOnFocusChangeListener((v, focus) -> { paintMode(); if(focus) navigationSound(0); });
        modeChip.setOnClickListener(v -> { sound(); modeCenter(); });
        modeChip.setOnKeyListener((v,key,e) -> {
            if (e.getAction()!=KeyEvent.ACTION_DOWN) return false;
            if (key==KeyEvent.KEYCODE_DPAD_DOWN) { focusPage(); return true; }
            return key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_UP;
        });
        LinearLayout.LayoutParams chipSpace = new LinearLayout.LayoutParams(-2, px(36)); chipSpace.rightMargin = px(8);
        navigation.addView(modeChip, chipSpace);
        String[] titles = {"Home", "Apps", "Settings"};
        String[] pages = {"home", "apps", "settings"};
        for (int i=0;i<titles.length;i++) {
            final int index=i;
            TextView tab = text(titles[i], 14, 0xB3FFFFFF, "sans-serif-medium");
            tab.setPadding(px(17), px(9), px(17), px(9)); tab.setGravity(Gravity.CENTER);
            tab.setFocusable(true); tab.setFocusableInTouchMode(true); tab.setSoundEffectsEnabled(false);
            tab.setOnClickListener(v -> { sound(); showPage(pages[index], true); });
            // One pill slides between tabs; leaving the bar it settles, translucent, on the current page.
            tab.setOnFocusChangeListener((v, focus) -> { if(focus) moveTabIndicator(true); else tab.post(()->{ if(focusedTab()<0) moveTabIndicator(true); }); });
            tab.setOnKeyListener((v,key,e) -> {
                if (e.getAction()==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_DOWN) {
                    View widget=index==0&&board!=null?board.first():null;
                    if (!page.equals(pages[index])) showPage(pages[index], true); else if(widget!=null)widget.requestFocus();else focusFirst();
                    return true;
                }
                return false;
            });
            tabs.add(tab); navigation.addView(tab, new LinearLayout.LayoutParams(-2, px(36)));
        }
        tabs.get(0).setId(View.generateViewId()); tabs.get(0).setNextFocusLeftId(modeChip.getId()); modeChip.setNextFocusRightId(tabs.get(0).getId());
        FrameLayout bar = new FrameLayout(this); bar.setClipChildren(false); tabBar=bar;
        tabIndicatorShape = shape(0x24FFFFFF, 18); tabIndicator = new View(this); tabIndicator.setBackground(tabIndicatorShape);
        bar.addView(tabIndicator, new FrameLayout.LayoutParams(0, px(36), Gravity.CENTER_VERTICAL));
        bar.addView(navigation, new FrameLayout.LayoutParams(-2, -1));
        bar.setPadding(px(4),px(2),px(4),px(2)); bar.setBackground(shape(0x1FFFFFFF,22));
        root.addView(bar, pos(-2, 40, 0, 26));
        bar.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> { if(r-l!=or-ol) bar.post(()->{ bar.setTranslationX((getResources().getDisplayMetrics().widthPixels-bar.getWidth())/2f); moveTabIndicator(false); }); });
        clock = text("", 18, 0xF2FFFFFF, "sans-serif-medium"); clock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); clock.setLetterSpacing(.01f);
        root.addView(clock, pos(130, 36, 782, 28));
        paintChrome();
        setContentView(root);
        showPage("home", false);
    }

    private void discover() {
        if (loading) return;
        loading=true;
        loader.execute(() -> {
            ArrayList<App> found = new ArrayList<>();
            HashSet<String> seen = new HashSet<>();
            PackageManager pm = getPackageManager();
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
                for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                    String pkg=info.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || Arrays.asList(STOCK_HOMES).contains(pkg) || !seen.add(pkg)) continue;
                    if (pm.getLaunchIntentForPackage(pkg)==null && pm.getLeanbackLaunchIntentForPackage(pkg)==null) continue;
                    try {
                        App app = new App(); app.pkg=pkg;
                        app.label=info.loadLabel(pm).toString();
                        app.icon=bitmap(info.loadIcon(pm), 120, 120);
                        app.color=colorFor(pkg);
                        app.description=descriptionFor(pkg);
                        app.tag=tagFor(pkg);
                        Drawable banner=info.activityInfo.applicationInfo.loadBanner(pm);
                        if (banner!=null) app.banner=bitmap(banner, 320, 180);
                        found.add(app);
                    } catch (RuntimeException ignored) { /* A package can disappear during an update. */ }
                }
            }
            Collections.sort(found, (a,b)->a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                loading=false;
                boolean changed = !loaded || found.size()!=apps.size();
                if (!changed) for (int i=0;i<found.size();i++) if (!found.get(i).pkg.equals(apps.get(i).pkg)) { changed=true; break; }
                if (changed) { apps.clear(); apps.addAll(found); HashMap<String,App> index=new HashMap<>(); for(App a:found) index.put(a.pkg,a); appIndex=index; loaded=true; showPage(page, true); }
            });
        });
    }

    private Bitmap bitmap(Drawable drawable, int w, int h) {
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bitmap); drawable.setBounds(0,0,w,h); drawable.draw(c); return bitmap;
    }
    private ArrayList<String> order() {
        ArrayList<String> ids=new ArrayList<>();
        String saved=prefs.getString("order", "");
        if (!saved.isEmpty()) ids.addAll(Arrays.asList(saved.split(",")));
        for (String pkg:FIRST) if (!ids.contains(pkg)) ids.add(pkg);
        for (App app:apps) if (!ids.contains(app.pkg)) ids.add(app.pkg);
        return ids;
    }
    private void hideDefaultApps() {
        if (prefs.getBoolean("default_hidden_v1", false)) return;
        Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
        hidden.addAll(Arrays.asList(DEFAULT_HIDDEN));
        prefs.edit().putStringSet("hidden", hidden).putBoolean("default_hidden_v1", true).apply();
    }
    private ArrayList<App> visibleApps(boolean all) {
        ArrayList<App> result=new ArrayList<>();
        Set<String> hidden=prefs.getStringSet("hidden", Collections.emptySet());
        for (String id:order()) for (App app:apps) if (app.pkg.equals(id) && (all || !hidden.contains(id))) result.add(app);
        return result;
    }

    private void showPage(String next, boolean focus) {
        if(next.equals("settings")&&!profiles.allows(Profiles.Content.SETTINGS))next="home";
        String previous=page;
        boolean transition=loaded && !previous.equals(next) && motion();
        for(Animator animation:pageAnimations)animation.cancel();pageAnimations.clear();
        for(Tile tile:tiles) tile.movement.stop();
        content.animate().cancel();content.setAlpha(1);content.setTranslationX(0);
        if(board!=null)homePage=board.index;
        content.removeAllViews();houseWidgets=null;board=null;content.setTag(null);
        page=next;homeGreeting=page.equals("home");heroPackage=homeGreeting?"@greeting":"";tiles.clear();
        light=pale(variant(wallpaperStyle()));paintChrome();
        clock.setVisibility(prefs.getBoolean("clock", true) ? View.VISIBLE : View.GONE);
        moveTabIndicator(transition);
        wallpaper.update();wallpaper.rest();wallpaper.syncAmbient();
        sceneryShade.animate().cancel();
        sceneryShade.setAlpha(page.equals("settings")?1:0);
        if (page.equals("settings")) { settings(focus); animatePage(previous,transition); return; }
        boolean home=page.equals("home");
        hero=new FrameLayout(this){@Override public boolean hasOverlappingRendering(){return false;}};
        boolean normal=profiles.mode==Profiles.Mode.NORMAL;
        heroTitle=text(home ? welcome() : normal ? "All your apps" : "Apps", 42, ink(0xFF), "sans-serif-medium");
        heroTitle.setLetterSpacing(-.035f); heroTitle.setSingleLine(true); heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END); shadow(heroTitle,10);
        hero.addView(heroTitle,pos(home?392:860,54,0,0));
        heroDescription=text(home ? greetingLine() : normal ? "Everything installed on this TV." : profiles.mode==Profiles.Mode.GUEST ? "The apps on Home." : "Apps approved for Child mode.", 16, ink(0xB3), "sans-serif");
        heroDescription.setSingleLine(true); heroDescription.setEllipsize(android.text.TextUtils.TruncateAt.END); shadow(heroDescription,8); hero.addView(heroDescription,pos(home?392:860,22,1,60));
        HomePresets.Layout shown=layout();
        ArrayList<String> widgets=new ArrayList<>();for(String id:shown.widgets)if(profiles.allowsWidget(id))widgets.add(id);
        // Without widgets, the greeting moves down to sit just above the favourites and the wallpaper stays open above it.
        ArrayList<ArrayList<String>> layout=home?WidgetBoard.pages(widgets,house,!InputsWidget.list(this).isEmpty()):new ArrayList<>();
        boolean housePage=false;for(ArrayList<String> p:layout)housePage|=p.contains("house");
        content.addView(hero,pos(864,88,48,home?(!layout.isEmpty()?96:272):100));
        heroKey=home?"@greeting":"";
        if(home&&housePage){
            houseWidgets=new HouseWidgets(this,unit,shown.scenes,houseRead,new HouseWidgets.Callbacks(){
                public void up(){tabs.get(0).requestFocus();}
                public void down(float x){focusNearestTile(x);}
                public void open(){if(!allowsApp("dev.housecontrol.tv")){Toast.makeText(MainActivity.this,profiles.mode.name+" mode does not open House Control.",Toast.LENGTH_SHORT).show();return;}try{startActivity(new Intent().setComponent(new ComponentName("dev.housecontrol.tv","dev.housecontrol.tv.MainActivity")));overridePendingTransition(0,0);}catch(Exception e){Toast.makeText(MainActivity.this,"Install House Control to connect your home.",Toast.LENGTH_SHORT).show();}}
                public void focusHome(String title,String detail){holdGreeting=false;homeGreeting=false;setHero("@home:"+title,title,detail);navigationSound(2);}
                public void activate(){sound();}
                public void scene(String id){if(overlay==null)new CapabilityCenter(CapabilityPanel.Section.SCENES,false,id);}
                public void read(HouseScenes.Read read){if(house)houseRead=read;}
                public boolean motion(){return MainActivity.this.motion();}
                public String sceneDetail(String id,String name){return MainActivity.this.sceneDetail(id);}
                public boolean light(){return light;}
                public void edge(int direction){if(board==null)return;View from=getCurrentFocus(),to=board.flip(direction,from);if(to!=null&&to!=from)to.requestFocus();}
            });
            content.addView(houseWidgets,pos(HouseWidgets.W,HouseWidgets.PAGE_H,0,0));
        }
        if(home&&!layout.isEmpty()){
            board=new WidgetBoard(this,boardHost,layout,homePage);homePage=board.index;
            content.addView(board,pos(WidgetBoard.W,WidgetBoard.H,0,0));
            if(houseWidgets!=null&&!board.onHouse()){houseWidgets.setVisibility(View.INVISIBLE);houseWidgets.setActive(false);}
            board.setActive(started);
        }
        scroll=new ScrollView(this); scroll.setFillViewport(false); scroll.setClipChildren(false); scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rows=new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); rows.setClipChildren(false); rows.setClipToPadding(false);
        rows.setPadding(px(48),px(home?16:10),px(48),px(30));
        scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));
        content.addView(scroll,pos(960, home ? 150 : 330,0,home ? 388 : 200));
        ArrayList<App> list=new ArrayList<>();
        if(home){for(String pkg:homeApps())list.add(appIndex.get(pkg));}
        else for(App app:visibleApps(true))if(allowsApp(app.pkg))list.add(app);
        if (!loaded) {
            rows.addView(text("Finding your apps…",15,ink(0xBB),"sans-serif"));
        } else if (list.isEmpty()) {
            rows.addView(text(profiles.noApps()?"No apps are approved for Child mode yet. An adult can choose them in Normal mode."
                :normal?"Your home is empty. Find your apps in the Apps tab.":"None of this mode's apps are installed.",15,ink(0xBB),"sans-serif"));
        } else {
            int count=home ? Math.min(6,list.size()) : list.size();
            for (int start=0;start<count;start+=6) {
                LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false); row.setClipToPadding(false); row.setGravity(Gravity.TOP);
                for (int i=start;i<Math.min(start+6,count);i++) {
                    App app=list.get(i); Tile tile=new Tile(app,i);
                    LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(px(132),px(108));
                    if (i>start) tp.leftMargin=px(14.4f);
                    row.addView(tile,tp); tiles.add(tile);
                }
                rows.addView(row,new LinearLayout.LayoutParams(-1,px(112)));
            }
        }
        if (focus) root.post(()->{holdGreeting=page.equals("home");focusFirst();});
        animatePage(previous,transition);
    }

    private void animatePage(String previous,boolean transition) {
        if(!transition)return;
        int before=previous.equals("home")?0:previous.equals("apps")?1:2;
        int after=page.equals("home")?0:page.equals("apps")?1:2;
        float direction=after>=before?1:-1;
        // Animate real element bounds rather than blending two full-screen textures.
        // This preserves the soft page entrance within a small TV chip's fill-rate budget.
        ArrayList<View> entering=new ArrayList<>();
        for(int i=0;i<content.getChildCount();i++)if(!(content.getChildAt(i) instanceof ScrollView)&&!"scroll-frame".equals(content.getChildAt(i).getTag()))entering.add(content.getChildAt(i));
        if(page.equals("settings")) {
            Object tag=content.getTag();if(tag instanceof ArrayList)for(Object row:(ArrayList<?>)tag)if(row instanceof View)entering.add((View)row);
        } else {
            for(int i=0;i<rows.getChildCount();i++)if(rows.getChildAt(i) instanceof TextView)entering.add(rows.getChildAt(i));
            entering.addAll(tiles);
        }
        for(int i=0;i<entering.size();i++) {
            View view=entering.get(i);float offset=px(16)*direction;
            view.setAlpha(0);view.setTranslationX(offset);
            ObjectAnimator fade=ObjectAnimator.ofFloat(view,View.ALPHA,0,1);fade.setDuration(270);
            ObjectAnimator slide=ObjectAnimator.ofFloat(view,View.TRANSLATION_X,offset,0);slide.setDuration(340);
            AnimatorSet set=new AnimatorSet();set.playTogether(fade,slide);set.setInterpolator(ease);set.setStartDelay(Math.min(i,5)*10);
            pageAnimations.add(set);set.start();
        }
    }

    private void focusFirst() {
        for (Tile tile:tiles) if (tile.app.pkg.equals(selectedPackage)) { tile.requestFocus(); return; }
        if (!tiles.isEmpty()) tiles.get(0).requestFocus();
    }
    /** Under the greeting: today's date, quietly. */
    private String greetingLine(){return today();}
    /** "Hello, <name>" with the name saved in Settings, else the build's greeting: "Hello", or LUMA_GREETING /
     *  .local/greeting.txt (see build.sh). */
    private String welcome(){if(!profiles.allows(Profiles.Content.PERSONAL))return greeting();String name=prefs.getString("name","").trim();return name.isEmpty()?getString(R.string.greeting):"Hello, "+name;}
    /** Settings → Your name: typed with the TV keyboard and kept only in this app's storage on the TV. */
    private void nameEditor(){
        FrameLayout o=openOverlay(0xA6000000);
        FrameLayout panel=new FrameLayout(this);panel.setBackground(shape(0xF51C1C1E,24));panel.setElevation(px(24));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(px(440),px(232));lp.gravity=Gravity.CENTER;o.addView(panel,lp);
        panel.addView(text("Your name",22,Color.WHITE,"sans-serif-medium"),pos(392,30,24,24));
        panel.addView(text("Home will say Hello and your name. Leave it empty for a plain Hello.",13,0xB3FFFFFF,"sans-serif"),pos(392,20,24,60));
        EditText field=new EditText(this);field.setSingleLine(true);field.setText(prefs.getString("name",""));field.setSelection(field.getText().length());
        field.setHint("Name");field.setHintTextColor(0x66FFFFFF);field.setTextColor(Color.WHITE);field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(17));
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(24)});field.setPadding(px(16),0,px(16),0);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        Runnable ring=()->{GradientDrawable bg=shape(0x1FFFFFFF,12);if(field.hasFocus())bg.setStroke(px(1.5f),Color.WHITE);field.setBackground(bg);};ring.run();
        field.setOnFocusChangeListener((v,f)->{ring.run();if(f)navigationSound(0);});
        panel.addView(field,pos(392,46,24,94));
        Runnable save=()->{prefs.edit().putString("name",field.getText().toString().trim()).apply();
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(),0);
            closeOverlay(false);showPage("settings",false);controlsFocus(1);};
        field.setOnEditorActionListener((v,action,e)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){save.run();return true;}return false;});
        LinearLayout buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);panel.addView(buttons,pos(392,42,24,166));
        LinearLayout a=new LinearLayout(this),b=new LinearLayout(this);a.setOrientation(LinearLayout.VERTICAL);b.setOrientation(LinearLayout.VERTICAL);
        buttons.addView(a,new LinearLayout.LayoutParams(px(120),-1));LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(px(120),-1);gap.leftMargin=px(10);buttons.addView(b,gap);
        TextView done=(TextView)menuRow(a,"Save",false,save);done.setGravity(Gravity.CENTER);
        TextView clear=(TextView)menuRow(b,"Clear",false,()->{field.setText("");field.requestFocus();});clear.setGravity(Gravity.CENTER);
        root.post(()->{field.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(field,0);});
    }
    private String greeting(){int h=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);return h<5?"Good night":h<12?"Good morning":h<18?"Good afternoon":"Good evening";}
    private String today(){return new SimpleDateFormat("EEEE, MMMM d",Locale.ENGLISH).format(new Date());}
    private void focusNearestTile(float x){Tile best=homeReturnTile(x);if(best!=null)best.requestFocus();}
    private Tile homeReturnTile(float x){
        Tile remembered=null;
        for(Tile t:tiles)if(t.index<6&&t.app.pkg.equals(homeReturnPackage)){remembered=t;break;}
        if(remembered!=null){lastDirectionY=1;lastDirectionX=0;return remembered;}
        return nearestTile(x);
    }
    private Tile nearestTile(float x){
        Tile best=null;float d=Float.MAX_VALUE;int[] l=new int[2];
        for(Tile t:tiles){if(t.index>=6)break;t.getLocationOnScreen(l);float e=Math.abs(l[0]+t.getWidth()/2f-x);if(e<d){d=e;best=t;}}
        if(best!=null){lastDirectionY=1;lastDirectionX=0;}
        return best;
    }
    /** What the Home widgets need from the launcher: sizes, colours, sounds, the hero line and app details. */
    private final WidgetBoard.Host boardHost=new WidgetBoard.Host(){
        public float unit(){return unit;}
        public boolean light(){return light;}
        public boolean motion(){return MainActivity.this.motion();}
        public void step(int index){navigationSound(index);}
        public void open(){sound();}
        public void hero(String key,String title,String detail){holdGreeting=false;homeGreeting=false;setHero(key,title,detail);}
        public View up(){return tabs.get(0);}
        public View down(float x){return homeReturnTile(x);}
        public void pageChanged(boolean onHouse,int direction){if(board!=null)homePage=board.index;showHouse(onHouse,direction);}
        public View houseEntry(int direction){if(houseWidgets==null)return null;return direction<0||houseWidgets.scenes.isEmpty()?houseWidgets.cover:houseWidgets.scenes.get(0);}
        public void nudgeHouse(int direction){
            if(houseWidgets==null||!motion())return;final HouseWidgets h=houseWidgets;h.animate().cancel();
            h.animate().translationX(direction*px(10)).setDuration(110).setInterpolator(ease).withEndAction(()->h.animate().translationX(0).setDuration(380).setInterpolator(new OvershootInterpolator(1.4f)).start()).start();
        }
        public App app(String pkg){return appIndex.get(pkg);}
        public View art(String pkg){App a=appIndex.get(pkg);return a==null?null:new Art(a);}
        public void launch(String pkg){App a=appIndex.get(pkg);if(a!=null)MainActivity.this.launch(a);else Toast.makeText(MainActivity.this,"This app is not installed.",Toast.LENGTH_SHORT).show();}
        public void start(Intent intent){
            ComponentName target=intent.resolveActivity(getPackageManager());
            if(profiles.mode!=Profiles.Mode.NORMAL&&(target==null||!allowsApp(target.getPackageName()))){Toast.makeText(MainActivity.this,profiles.mode.name+" mode does not open this.",Toast.LENGTH_SHORT).show();return;}
            try{startActivity(intent);overridePendingTransition(0,0);}catch(RuntimeException e){Toast.makeText(MainActivity.this,"This can't be opened on this TV.",Toast.LENGTH_SHORT).show();}}
        public SharedPreferences prefs(){return prefs;}
        public Activity activity(){return MainActivity.this;}
    };
    /** The Smart Home page slides in or out with the widget pages; it polls House Control only while shown. */
    private void showHouse(boolean show,int direction){
        if(houseWidgets==null)return;final HouseWidgets h=houseWidgets;h.animate().cancel();
        if(show){
            // The 3D renderer wakes once the page has landed: resuming WebGL mid-slide cost frames on this stick.
            h.setVisibility(View.VISIBLE);if(!motion())h.setActive(started);else h.postDelayed(()->{if(houseWidgets==h&&board!=null&&board.onHouse())h.setActive(started);},480);
            if(motion()){h.setTranslationX(direction*px(72));h.setAlpha(0);h.animate().translationX(0).alpha(1).setDuration(460).setStartDelay(40).setInterpolator(ease).withLayer().start();}
            else{h.setTranslationX(0);h.setAlpha(1);}
        } else {
            h.setActive(false);
            Runnable hide=()->{if(houseWidgets==h&&board!=null&&!board.onHouse()){h.setVisibility(View.INVISIBLE);h.setTranslationX(0);h.setAlpha(1);}};
            if(motion())h.animate().translationX(-direction*px(56)).alpha(0).setDuration(240).setInterpolator(ease).withLayer().withEndAction(hide).start();else hide.run();
        }
    }
    /** Apps opened from Luma, newest first: Up Next falls back to them without usage access. */
    private void remember(String pkg){
        ArrayList<String> list=new ArrayList<>();list.add(pkg+":"+System.currentTimeMillis());
        for(String e:prefs.getString("recent","").split(","))if(!e.isEmpty()&&!e.startsWith(pkg+":")&&list.size()<12)list.add(e);
        prefs.edit().putString("recent",String.join(",",list)).apply();
    }
    private String sceneDetail(String id){App app=appIndex.get(sceneApp(id));return "OK shows what it changes"+(app!=null&&allowsApp(app.pkg)?" · Opens "+app.label:"");}
    private void readHouse(Runnable done){
        if(!house){done.run();return;}
        houseLink.read(read->{if(house)houseRead=read;done.run();});
    }
    private void showGreeting() {
        homeGreeting=true;
        if(heroTitle==null || !page.equals("home"))return;
        setHero("@greeting",welcome(),greetingLine());
    }
    /** Cross-fades the hero to a new title and line. */
    private void setHero(String key,String title,String detail){
        if(heroTitle==null||key.equals(heroKey))return;heroKey=key;
        final TextView t=heroTitle,d=heroDescription;t.animate().cancel();d.animate().cancel();
        if(!motion()){t.setText(title);d.setText(detail);t.setAlpha(1);d.setAlpha(1);t.setTranslationY(0);d.setTranslationY(0);return;}
        t.animate().alpha(0).translationY(-px(4)).setDuration(90).withEndAction(()->{
            if(heroTitle!=t||!heroKey.equals(key))return;
            t.setText(title);d.setText(detail);t.setTranslationY(px(8));d.setTranslationY(px(5));d.setAlpha(0);
            t.animate().alpha(1).translationY(0).setDuration(300).setInterpolator(ease).start();
            d.animate().alpha(1).translationY(0).setDuration(360).setStartDelay(30).setInterpolator(ease).start();
        }).start();
        d.animate().alpha(0).setDuration(90).start();
    }
    private void selectApp(App app) {
        selectedPackage=app.pkg;
        if(holdGreeting){holdGreeting=false;return;}
        if(heroTitle==null || !page.equals("home")) return;
        homeGreeting=false;setHero(app.pkg,app.label,app.description);
    }

    private boolean launch(App app) {
        if (!allowsApp(app.pkg)) { Toast.makeText(this,profiles.mode.name+" mode does not open "+app.label+".",Toast.LENGTH_SHORT).show(); return false; }
        try {
            Intent intent=getPackageManager().getLeanbackLaunchIntentForPackage(app.pkg);
            if (intent==null) intent=getPackageManager().getLaunchIntentForPackage(app.pkg);
            if (intent==null) throw new ActivityNotFoundException();
            sound(); startActivity(intent);overridePendingTransition(0,0);remember(app.pkg);
            return true;
        } catch (RuntimeException error) { Toast.makeText(this,"Could not open this app. It may be updating.",Toast.LENGTH_SHORT).show(); discover(); return false; }
    }

    private String sceneApp(String scene){try{JSONObject saved=new JSONObject(prefs.getString("scene_app_packages","{}"));if(saved.has(scene))return saved.optString(scene);}catch(Exception ignored){}return scene.equals("movie")?"com.plexapp.android":"";}
    private String appLabel(String pkg){if(pkg.isEmpty())return "None";for(App app:apps)if(app.pkg.equals(pkg))return app.label;return pkg;}
    /* ---------- overlays: menus, notices and the wallpaper picker (no stock dialogs) ---------- */
    private View nav(){return (View)tabs.get(0).getParent().getParent();}
    private View tabIndicator;private GradientDrawable tabIndicatorShape;private ValueAnimator tabMove;private int tabColor=0x24FFFFFF;
    private int focusedTab(){for(int i=0;i<tabs.size();i++)if(tabs.get(i).hasFocus())return i;return -1;}
    private int pageTab(){return page.equals("apps")?1:page.equals("settings")?2:0;}
    /** Slides the tab pill under the focused tab (white), or the current page's tab (glass) when focus is elsewhere. */
    private void moveTabIndicator(boolean animate){
        if(tabIndicator==null||tabs.isEmpty())return;
        int focused=focusedTab(),index=focused>=0?focused:pageTab();View target=tabs.get(index),row=(View)target.getParent();
        if(target.getWidth()==0){target.post(()->moveTabIndicator(false));return;}
        final float fromX=tabIndicator.getTranslationX(),toX=row.getLeft()+target.getLeft()-((View)row.getParent()).getPaddingLeft();
        final int fromW=tabIndicator.getLayoutParams().width,toW=target.getWidth(),fromColor=tabColor,toColor=focused>=0?(light?0xFF1D1D1F:0xF5FFFFFF):(light?0xE6FFFFFF:0x24FFFFFF);
        final int[] fromText=new int[tabs.size()],toText=new int[tabs.size()];
        for(int i=0;i<tabs.size();i++){fromText[i]=((TextView)tabs.get(i)).getCurrentTextColor();toText[i]=i==focused?(light?Color.WHITE:0xFF17202B):ink(i==pageTab()?0xFF:0xA6);}
        if(tabMove!=null)tabMove.cancel();
        ArgbEvaluator argb=new ArgbEvaluator();
        ValueAnimator.AnimatorUpdateListener step=a->{float f=(float)a.getAnimatedValue();
            tabIndicator.setTranslationX(fromX+(toX-fromX)*f);ViewGroup.LayoutParams lp=tabIndicator.getLayoutParams();lp.width=Math.round(fromW+(toW-fromW)*f);tabIndicator.setLayoutParams(lp);
            tabColor=(int)argb.evaluate(f,fromColor,toColor);tabIndicatorShape.setColor(tabColor);
            float t=Math.max(0,Math.min(1,(f-.12f)/.3f)); // text flips quickly, so it never matches the pill mid-way
            for(int i=0;i<tabs.size();i++)((TextView)tabs.get(i)).setTextColor((int)argb.evaluate(t,fromText[i],toText[i]));};
        tabMove=ValueAnimator.ofFloat(0,1);tabMove.addUpdateListener(step);
        if(!animate||!motion()||fromW==0){tabMove.setDuration(0);}else{tabMove.setDuration(280);tabMove.setInterpolator(ease);}
        tabMove.start();
    }
    private FrameLayout overlay;private View overlayReturn;private Runnable overlayCancel;
    private void closeOverlay(boolean cancelled){
        if(overlay==null)return;final FrameLayout o=overlay;overlay=null;Runnable c=overlayCancel;overlayCancel=null;if(cancelled&&c!=null)c.run();
        trapFocus(false);
        // A fade's end action is skipped when the animation is cancelled, which left the picker stuck over Home:
        // the closing overlay takes no focus, and is removed on a timer, not by the animation.
        o.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);o.animate().cancel();
        if(motion()){o.animate().alpha(0).setDuration(180).start();root.postDelayed(()->root.removeView(o),200);}else root.removeView(o);
        if(overlayReturn!=null){View r=overlayReturn;overlayReturn=null;root.post(r::requestFocus);}
    }
    private void trapFocus(boolean on){int mode=on?ViewGroup.FOCUS_BLOCK_DESCENDANTS:ViewGroup.FOCUS_BEFORE_DESCENDANTS;content.setDescendantFocusability(mode);((ViewGroup)nav()).setDescendantFocusability(mode);}
    private FrameLayout openOverlay(int scrim){
        // One overlay replacing another (About → Support) keeps the original place to return to.
        View back=overlay!=null?overlayReturn:getCurrentFocus();overlayReturn=null;closeOverlay(false);overlayReturn=back;
        trapFocus(true); // the page and tabs behind a dialog take no focus while it is open
        overlay=new FrameLayout(this);overlay.setBackgroundColor(scrim);overlay.setClipChildren(false);overlay.setTranslationZ(px(120));
        root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        if(motion()){overlay.setAlpha(0);overlay.animate().alpha(1).setDuration(220).setInterpolator(ease).start();}
        return overlay;
    }
    private TextView menuRow(LinearLayout list,String label,boolean checked,Runnable action){
        TextView row=text(label,15,Color.WHITE,"sans-serif-medium");row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(px(18),0,px(18),0);
        row.setSingleLine(true);row.setEllipsize(android.text.TextUtils.TruncateAt.END);row.setFocusable(true);row.setFocusableInTouchMode(true);row.setSoundEffectsEnabled(false);
        if(checked)row.setCompoundDrawablePadding(px(8));
        Runnable paint=()->{boolean f=row.hasFocus();row.setBackground(f?shape(0xF7FFFFFF,12):null);row.setTextColor(f?0xFF111827:Color.WHITE);row.setText((checked?"✓  ":"")+label);};
        paint.run();
        row.setOnFocusChangeListener((v,f)->{paint.run();row.animate().cancel();if(motion())row.animate().scaleX(f?1.02f:1).scaleY(f?1.02f:1).setDuration(240).setInterpolator(ease).start();if(f)navigationSound(list.indexOfChild(row));});
        row.setOnClickListener(v->{sound();action.run();});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,px(42));lp.bottomMargin=px(4);list.addView(row,lp);return row;
    }
    /** A centred glass menu. pick receives the chosen index. */
    private void menu(String title,String[] items,int checked,java.util.function.IntConsumer pick){
        FrameLayout o=openOverlay(0xA6000000);
        FrameLayout panel=new FrameLayout(this);panel.setBackground(shape(0xF51C1C1E,24));panel.setElevation(px(24));
        int rows=Math.min(items.length,7);float h=74+rows*46+18;
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(px(400),px(h));lp.gravity=Gravity.CENTER;o.addView(panel,lp);
        TextView t=text(title,20,Color.WHITE,"sans-serif-medium");t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);panel.addView(t,pos(352,28,24,24));
        ScrollView sv=new ScrollView(this);sv.setVerticalScrollBarEnabled(false);sv.setClipToPadding(false);sv.setPadding(px(10),0,px(10),0);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(px(4),px(4),px(4),px(10));list.setClipToPadding(false);list.setClipChildren(false);sv.addView(list);
        panel.addView(sv,pos(400,h-66,0,62));
        View first=null;
        for(int i=0;i<items.length;i++){final int index=i;View r=menuRow(list,items[i],i==checked,()->{closeOverlay(false);pick.accept(index);});if(i==Math.max(0,checked)||first==null&&i==0)first=r;}
        if(motion()){panel.setScaleX(.96f);panel.setScaleY(.96f);panel.animate().scaleX(1).scaleY(1).setDuration(360).setInterpolator(spring).start();}
        final View focus=first;root.post(()->{if(focus!=null)focus.requestFocus();});
    }
    private void notice(String title,String message){
        FrameLayout o=openOverlay(0xA6000000);
        FrameLayout panel=new FrameLayout(this);panel.setBackground(shape(0xF51C1C1E,24));panel.setElevation(px(24));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(px(440),px(260));lp.gravity=Gravity.CENTER;o.addView(panel,lp);
        panel.addView(text(title,22,Color.WHITE,"sans-serif-medium"),pos(392,30,24,26));
        TextView body=text(message,14,0xC7FFFFFF,"sans-serif");body.setLineSpacing(px(5),1);panel.addView(body,pos(392,140,24,66));
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);panel.addView(list,pos(140,42,286,200));
        View done=menuRow(list,"Done",false,()->closeOverlay(false));((TextView)done).setGravity(Gravity.CENTER);root.post(done::requestFocus);
    }
    /** Full-screen wallpaper picker: moving between thumbnails previews each wallpaper live. */
    private void wallpaperPicker(){
        if(homeLayout()!=null){notice("Wallpaper",lockedLine());return;}
        final int saved=wallpaperStyle();
        FrameLayout o=openOverlay(Color.TRANSPARENT);
        overlayCancel=()->{wallpaper.show(saved);content.animate().alpha(1).setDuration(260).start();nav().animate().alpha(1).setDuration(260).start();sceneryShade.animate().alpha(1).setDuration(260).start();};
        content.animate().alpha(0).setDuration(240).start();nav().animate().alpha(0).setDuration(240).start();sceneryShade.animate().alpha(0).setDuration(300).start();
        View fade=new View(this);fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0x99000000,0xE6000000}));o.addView(fade,pos(960,260,0,280));
        TextView name=text(WALLPAPERS[saved],34,Color.WHITE,"sans-serif-medium");name.setLetterSpacing(-.02f);name.setShadowLayer(px(10),0,px(1),0x66000000);o.addView(name,pos(600,44,48,338));
        TextView hint=text("OK to choose  ·  Back to keep the current one",13,0xB3FFFFFF,"sans-serif");o.addView(hint,pos(600,20,49,386));
        LinearLayout row=new LinearLayout(this);row.setClipChildren(false);row.setClipToPadding(false);o.addView(row,pos(864,80,48,430));
        ArrayList<View> cells=new ArrayList<>();
        for(int i=0;i<WALLPAPERS.length;i++){
            final int index=i;
            FrameLayout cell=new FrameLayout(this);cell.setFocusable(true);cell.setFocusableInTouchMode(true);cell.setSoundEffectsEnabled(false);cell.setClipToOutline(true);
            cell.setBackground(shape(0xFF101521,12));
            ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.CENTER_CROP);img.setImageResource(THUMBS[i]);cell.addView(img,new FrameLayout.LayoutParams(-1,-1));
            GradientDrawable ring=shape(Color.TRANSPARENT,12);ring.setStroke(px(1.5f),i==saved?0xCCFFFFFF:0x33FFFFFF);cell.setForeground(ring);
            cell.setOnFocusChangeListener((v,f)->{
                GradientDrawable r=shape(Color.TRANSPARENT,12);r.setStroke(px(f?2:1.5f),f?Color.WHITE:index==saved?0xCCFFFFFF:0x33FFFFFF);cell.setForeground(r);cell.setElevation(px(f?18:2));
                cell.animate().cancel();float s=f?1.16f:1;if(motion())cell.animate().scaleX(s).scaleY(s).translationY(f?-px(4):0).setDuration(420).setInterpolator(spring).start();else{cell.setScaleX(s);cell.setScaleY(s);}
                if(f){name.setText(WALLPAPERS[index]);wallpaper.show(index);navigationSound(index);}
            });
            cell.setOnClickListener(v->{sound();prefs.edit().putInt("wallpaper",index).apply();overlayCancel=null;closeOverlay(false);
                content.animate().alpha(1).setDuration(260).start();nav().animate().alpha(1).setDuration(260).start();sceneryShade.animate().alpha(1).setDuration(260).start();showPage("settings",false);controlsFocus(0);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(px(100),px(56));if(i>0)lp.leftMargin=px(9);row.addView(cell,lp);cells.add(cell);
        }
        root.post(()->cells.get(saved).requestFocus());
    }

    /** Non-comment lines of a res/raw text file, split on "|". */
    private ArrayList<String[]> rawLines(int id){
        ArrayList<String[]> out=new ArrayList<>();
        try(java.io.BufferedReader r=new java.io.BufferedReader(new java.io.InputStreamReader(getResources().openRawResource(id),java.nio.charset.StandardCharsets.UTF_8))){
            String line;while((line=r.readLine())!=null){line=line.trim();if(!line.isEmpty()&&!line.startsWith("#"))out.add(line.split("\\s*\\|\\s*",-1));}
        }catch(java.io.IOException ignored){}
        return out;
    }
    private String about(String key){for(String[] l:rawLines(R.raw.about))if(l[0].startsWith(key+"="))return l[0].substring(key.length()+1);return "";}
    private String version(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException e){return "";}}
    private ImageView qr(String name){ImageView img=new ImageView(this);img.setScaleType(ImageView.ScaleType.FIT_CENTER);img.setBackground(shape(Color.WHITE,12));img.setPadding(px(5),px(5),px(5),px(5));
        int id=getResources().getIdentifier(name,"drawable",getPackageName());if(id!=0)img.setImageResource(id);return img;}
    /** Credits, links and a QR code to the source. */
    private void aboutScreen(){
        FrameLayout o=openOverlay(0xA6000000);
        FrameLayout panel=new FrameLayout(this);panel.setBackground(shape(0xF51C1C1E,24));panel.setElevation(px(24));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(px(600),px(330));lp.gravity=Gravity.CENTER;o.addView(panel,lp);
        panel.addView(text("Luma TV",26,Color.WHITE,"sans-serif-medium"),pos(380,34,26,24));
        panel.addView(text("Version "+version(),13,0x99FFFFFF,"sans-serif"),pos(380,18,27,62));
        TextView body=text("A calm home screen for Android TV. No ads, no sponsored rows, no tracking: your settings stay on this TV. Hold OK on an app to organise your Home.",13,0xC7FFFFFF,"sans-serif");body.setLineSpacing(px(4),1);panel.addView(body,pos(380,66,27,94));
        panel.addView(text("Made by "+about("author"),15,Color.WHITE,"sans-serif-medium"),pos(380,22,27,172));
        String links=about("x").replaceFirst("^https?://","")+"\n"+about("github").replaceFirst("^https?://","");
        TextView link=text(links,12,0xB3FFFFFF,"sans-serif");link.setLineSpacing(px(4),1);panel.addView(link,pos(380,40,27,198));
        panel.addView(qr("qr_source"),pos(150,150,426,28));
        TextView caption=text("Source code",12,0x99FFFFFF,"sans-serif");caption.setGravity(Gravity.CENTER);panel.addView(caption,pos(150,18,426,184));
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.HORIZONTAL);panel.addView(list,pos(400,42,26,262));
        LinearLayout a=new LinearLayout(this),b=new LinearLayout(this);a.setOrientation(LinearLayout.VERTICAL);b.setOrientation(LinearLayout.VERTICAL);
        list.addView(a,new LinearLayout.LayoutParams(px(200),-1));LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(px(110),-1);gap.leftMargin=px(10);list.addView(b,gap);
        menuRow(a,"Support the author",false,this::supportScreen);View done=menuRow(b,"Done",false,()->closeOverlay(false));((TextView)done).setGravity(Gravity.CENTER);
        root.post(done::requestFocus);
    }
    /** Crypto tips: a QR code per coin from res/raw/support.txt (drawn by tools/support-qr.sh). */
    private void supportScreen(){
        FrameLayout o=openOverlay(0xF20B0B0D);
        TextView title=text("Support the author",30,Color.WHITE,"sans-serif-medium");title.setLetterSpacing(-.02f);o.addView(title,pos(700,40,48,38));
        TextView body=text("Luma is free and has no ads. If it earned a place on your TV, a tip in crypto helps it grow. Scan a code with your phone's wallet, and send each coin only over the network named on its card.",13,0xC7FFFFFF,"sans-serif");
        body.setLineSpacing(px(4),1);o.addView(body,pos(760,44,48,84));
        ArrayList<String[]> coins=rawLines(R.raw.support);int n=Math.max(1,Math.min(4,coins.size()));float w=(864-(n-1)*16)/(float)n;
        for(int i=0;i<Math.min(4,coins.size());i++){String[] c=coins.get(i);
            FrameLayout card=new FrameLayout(this);card.setBackground(shape(0x1AFFFFFF,18));o.addView(card,pos(w,322,48+i*(w+16),146));
            TextView name=text(c[0],14,Color.WHITE,"sans-serif-medium");name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(name,pos(w-28,20,14,14));
            TextView note=text(c.length>1?c[1]:"",10.5f,0x99FFFFFF,"sans-serif");note.setMaxLines(2);card.addView(note,pos(w-28,30,14,37));
            float side=Math.min(w-40,170);card.addView(qr("qr_"+i),pos(side,side,(w-side)/2,74));
            TextView address=text(c.length>2?c[2]:"",10,0xD9FFFFFF,"monospace");address.setGravity(Gravity.CENTER);address.setLineSpacing(px(2),1);card.addView(address,pos(w-24,58,12,82+side));
        }
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);o.addView(list,pos(140,42,410,482));
        View done=menuRow(list,"Done",false,()->closeOverlay(false));((TextView)done).setGravity(Gravity.CENTER);root.post(done::requestFocus);
    }
    // Stock launchers that luma.sh may have switched off to let Luma be the home screen.
    static final String[] STOCK_HOMES={"com.google.android.tvlauncher","com.google.android.apps.tv.launcherx","com.google.android.leanbacklauncher"};
    private Intent homeIntent(){return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);}
    private boolean lumaIsHome(){ResolveInfo r=getPackageManager().resolveActivity(homeIntent(),PackageManager.MATCH_DEFAULT_ONLY);return r!=null&&r.activityInfo!=null&&getPackageName().equals(r.activityInfo.packageName);}
    private void homeScreen(){
        if(!lumaIsHome()){notice("Home screen","To make Luma your home screen, run  ./luma.sh home  on a computer with this TV on the same Wi-Fi. Some TVs also offer it in Settings → Apps → Default apps.");return;}
        menu("Home screen",new String[]{"Keep Luma","Return to the TV's home screen"},0,choice->{if(choice==1)restoreHome();});
    }
    /** Hands Home back to the TV's own launcher. A launcher switched off over ADB can only be switched on over ADB. */
    private void restoreHome(){
        PackageManager pm=getPackageManager();boolean off=false;
        for(String pkg:STOCK_HOMES)try{int state=pm.getApplicationEnabledSetting(pkg);off|=state==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER||state==PackageManager.COMPONENT_ENABLED_STATE_DISABLED;}catch(IllegalArgumentException notInstalled){}
        ResolveInfo other=null;
        for(ResolveInfo r:pm.queryIntentActivities(homeIntent(),0)){String pkg=r.activityInfo.packageName;if(pkg.equals(getPackageName())||pkg.equals("com.android.tv.settings")||pkg.contains("setupwraith")||pkg.contains("provision"))continue;if(other==null||r.priority>other.priority)other=r;}
        if(off||other==null){notice("Return to the TV's home screen","Your TV's own home screen was switched off from a computer so that Luma could replace it. Only a computer can switch it back on: run  ./luma.sh restore  in the Luma folder, with this TV on the same Wi-Fi.");return;}
        pm.clearPackagePreferredActivities(getPackageName());
        startActivity(homeIntent().setComponent(new ComponentName(other.activityInfo.packageName,other.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private void sceneApps(){
        if(!house){notice("Scene apps","Scenes come from House Control TV. Install it to control your lights from Home.");return;}
        readHouse(()->{
            if(!started||overlay!=null||!page.equals("settings"))return;
            HouseScenes.Read read=houseRead;
            if(read==null||read.home==null||read.home.scenes.isEmpty()){notice("Scene apps",read!=null&&read.problem!=null?read.problem:"House Control lists no scenes for this home.");return;}
            List<HouseScenes.Scene> scenes=read.home.scenes;
            ArrayList<String> names=new ArrayList<>();for(HouseScenes.Scene scene:scenes)names.add(scene.name+"   →   "+appLabel(sceneApp(scene.id)));
            menu("Open an app after a scene",names.toArray(new String[0]),-1,index->{
                HouseScenes.Scene scene=scenes.get(index);
                ArrayList<String> labels=new ArrayList<>();labels.add("None — stay on Home");int current=0;String chosen=sceneApp(scene.id);
                for(int i=0;i<apps.size();i++){labels.add(apps.get(i).label);if(apps.get(i).pkg.equals(chosen))current=i+1;}
                menu("After "+scene.name,labels.toArray(new String[0]),current,choice->{
                    try{JSONObject saved=new JSONObject(prefs.getString("scene_app_packages","{}"));saved.put(scene.id,choice==0?"":apps.get(choice-1).pkg);prefs.edit().putString("scene_app_packages",saved.toString()).apply();}catch(Exception ignored){}
                    root.post(this::sceneApps);
                });
            });
        });
    }

    private void appMenu(App app) {
        if (homeLayout()!=null) { menu(app.label,new String[]{"Open","App info","Organize"},-1,index->{if(index==0)launch(app);else if(index==1)appInfo(app);else notice("Organize apps",lockedLine());}); return; }
        Set<String> hidden=new HashSet<>(prefs.getStringSet("hidden",Collections.emptySet()));
        String[] choices={"Open", "Move to first position", "Move left", "Move right", hidden.contains(app.pkg) ? "Show on Home" : "Hide from Home", "App info"};
        menu(app.label,choices,-1,index-> {
            if (index==0) { launch(app); return; }
            ArrayList<String> ids=order(); int current=ids.indexOf(app.pkg);
            if (index==1) { ids.remove(app.pkg); ids.add(0,app.pkg); hidden.remove(app.pkg); }
            if (index==2 && current>0) Collections.swap(ids,current,current-1);
            if (index==3 && current<ids.size()-1) Collections.swap(ids,current,current+1);
            if (index==4) { if (hidden.contains(app.pkg)) hidden.remove(app.pkg); else hidden.add(app.pkg); }
            if (index==5) { appInfo(app); return; }
            prefs.edit().putString("order",String.join(",",ids)).putStringSet("hidden",hidden).apply();
            selectedPackage=app.pkg;showPage(page,true);
        });
    }
    private void appInfo(App app){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+app.pkg)));}

    /* ---------- Settings ---------- */
    private ImageView previewImage;private TextView previewTitle,previewBody;
    private void preview(int thumb,String title,String body){
        if(previewImage==null)return;
        if(thumb!=0){previewImage.setImageResource(thumb);previewImage.setAlpha(1f);}else previewImage.setAlpha(.35f);
        previewTitle.setText(title);previewBody.setText(body);
    }
    private void settings(boolean focus) {
        TextView title=text("Settings",42,ink(0xFF),"sans-serif-medium"); title.setLetterSpacing(-.035f); shadow(title,10);
        content.addView(title,pos(450,52,48,84));
        // The list scrolls itself (reveal): the stock ScrollView stops a focused row flush with its edge, where the
        // row's lift and shadow were cut off. Rows scroll away under a soft edge, not over the title.
        ScrollView sv=new ScrollView(this){@Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect){return 0;}};
        sv.setVerticalScrollBarEnabled(false); sv.setClipToPadding(false); sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalFadingEdgeEnabled(true); sv.setFadingEdgeLength(px(40));
        // No padding on the ScrollView itself: Android fades only the area inside a view's padding, which left the
        // shadow outside it unfaded, as a visible rectangle. The room for the focus lift is the list's padding.
        LinearLayout list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(px(22),px(12),px(22),px(48)); list.setClipChildren(false); list.setClipToPadding(false);
        sv.addView(list);settingsScroll=sv;
        FrameLayout frame=new FrameLayout(this); frame.setTag("scroll-frame"); frame.addView(sv,new FrameLayout.LayoutParams(-1,-1)); content.addView(frame,pos(520,400,26,138));
        // live preview on the right
        FrameLayout card=new FrameLayout(this);card.setBackground(shape(glass(),22));card.setClipToOutline(true);
        previewImage=new ImageView(this);previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);card.addView(previewImage,pos(352,198,0,0));
        previewTitle=text("",19,ink(0xFF),"sans-serif-medium");card.addView(previewTitle,pos(320,26,18,214));
        previewBody=text("",13,ink(0xC2),"sans-serif");previewBody.setLineSpacing(px(4),1);card.addView(previewBody,pos(316,80,18,246));
        content.addView(card,pos(352,330,560,150));
        int wp=wallpaperStyle();
        ArrayList<View> controls=new ArrayList<>();
        caption(list,"Appearance",true);
        controls.add(option(list,"Wallpaper",()->WALLPAPERS[wallpaperStyle()],null,this::wallpaperPicker,
            ()->preview(THUMBS[wallpaperStyle()],WALLPAPERS[wallpaperStyle()],"Eight wallpapers, from silver silk to the Milky Way. Silver is light by day and dark after sunset.")));
        controls.add(option(list,"Your name",()->{String n=prefs.getString("name","").trim();return n.isEmpty()?"Add":n;},null,this::nameEditor,
            ()->preview(0,"Your name","Home greets you by name. It is saved only on this TV.")));
        controls.add(option(list,"Clock",null,"clock",()->{},()->preview(0,"Clock","Shows the time in the top-right corner of every page.")));
        caption(list,"Motion and sound",false);
        controls.add(option(list,"Fluid motion",null,"motion",()->{},()->preview(0,"Fluid motion","Springy focus and depth. Turn off for a calmer, simpler screen.")));
        controls.add(option(list,"Living wallpaper",null,"ambient",()->wallpaper.syncAmbient(),()->preview(THUMBS[wallpaperStyle()],"Living wallpaper","Clouds drift and water moves very slowly. Pauses whenever an app is open.")));
        controls.add(option(list,"Navigation sounds",null,"sound",()->{},()->preview(0,"Navigation sounds","Soft, quiet feedback as you move and open apps.")));
        caption(list,"Home",false);
        widgetsRow=controls.size();
        controls.add(option(list,"Home widgets",()->homeLayout()!=null?homeLayout()+" layout":widgetSummary(),null,this::widgetEditor,
            ()->preview(0,"Home widgets","Choose what Home shows above your apps — "+(house?"Smart Home, ":"")+"Up Next, Weather, "+(InputsWidget.count>0?"Inputs, ":"")+"Now Playing and Control — and their order. Left and Right turn the pages.")));
        controls.add(option(list,"Temperature",()->new String[]{"Automatic","Celsius","Fahrenheit"}[unitIndex()],null,this::temperatureMenu,
            ()->preview(0,"Temperature","Weather in Celsius or Fahrenheit. Automatic follows the TV's region. The forecast uses your saved location when set. Otherwise it uses the TV's time zone.")));
        if(house)controls.add(option(list,"Scene apps",()->"Choose",null,this::sceneApps,()->preview(0,"Scene apps","Open an app after a scene — Movie can start Plex as the lights go down.")));
        controls.add(option(list,"Home screen",()->lumaIsHome()?"Luma":"TV default",null,this::homeScreen,
            ()->preview(0,"Home screen",lumaIsHome()?"Luma opens when you press Home. Choose to return to your TV's own home screen.":"Your TV's own home screen opens when you press Home.")));
        caption(list,"System",false);
        capabilitiesRow=controls.size();
        controls.add(option(list,"Capabilities",this::capabilitySummary,null,this::capabilityCenter,
            ()->preview(0,"Capabilities","Safe TV actions, plain requests and the TV's health. Every action shows a preview and asks first.")));
        controls.add(option(list,"Device settings",()->"",null,()->startActivity(new Intent(Settings.ACTION_SETTINGS)),()->preview(0,"Device settings","Network, picture, sound and accounts.")));
        controls.add(option(list,"Support the author",()->"",null,this::supportScreen,()->preview(0,"Support the author","Luma is free and has no ads. A tip in crypto helps it grow.")));
        controls.add(option(list,"About Luma",()->"",null,this::aboutScreen,()->preview(0,"About Luma","Version "+version()+" · made by "+about("author"))));
        content.setTag(controls);
        preview(THUMBS[wp],WALLPAPERS[wp],"Eight wallpapers, from silver silk to the Milky Way. Silver is light by day and dark after sunset.");
        if (focus) root.post(()->controls.get(0).requestFocus());
    }
    private void caption(LinearLayout list,String label,boolean first){
        TextView c=text(label,13,ink(0x99),"sans-serif-medium");c.setPadding(px(10),0,0,0);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,px(20));lp.topMargin=px(first?0:12);lp.bottomMargin=px(6);list.addView(c,lp);
    }
    private ScrollView settingsScroll;private ValueAnimator settingsGlide;private int widgetsRow;
    /** Scrolls the settings list so the focused row, with room for its lift and shadow (and its group's caption
     *  above the first row of a group), clears the faded edges. */
    private void reveal(View row){
        ScrollView sv=settingsScroll;if(sv==null||row.getParent()==null||row.getParent().getParent()!=sv)return;
        ViewGroup list=(ViewGroup)row.getParent();int i=list.indexOfChild(row),top=row.getTop(),bottom=row.getBottom();
        if(i>0&&!list.getChildAt(i-1).isFocusable())top=list.getChildAt(i-1).getTop();
        if(i<=1)top=0;
        int margin=px(54),h=sv.getHeight(),y=sv.getScrollY(),max=Math.max(0,list.getHeight()-h),target=y;
        if(top-margin<y)target=top-margin;else if(bottom+margin>y+h)target=bottom+margin-h;
        target=Math.max(0,Math.min(max,target));
        if(settingsGlide!=null)settingsGlide.cancel();
        if(target==y)return;
        if(!motion()){sv.scrollTo(0,target);return;}
        settingsGlide=ValueAnimator.ofInt(y,target);settingsGlide.setDuration(420);settingsGlide.setInterpolator(ease);
        settingsGlide.addUpdateListener(a->sv.scrollTo(0,(int)a.getAnimatedValue()));settingsGlide.start();
    }
    private String widgetSummary(){boolean inputs=!InputsWidget.list(this).isEmpty();int n=0;for(String id:WidgetBoard.IDS)if(WidgetBoard.on(prefs,id)&&(house||!id.equals("house"))&&(inputs||!id.equals("inputs")))n++;return n==0?"Off":n+" on";}
    private String capabilitySummary(){Capabilities.Result last=Capabilities.Result.parse(prefs.getString(Capabilities.LAST,null));return last==null?"Explore":last.message;}
    private int unitIndex(){String u=prefs.getString("temp_unit","auto");return u.equals("c")?1:u.equals("f")?2:0;}
    private void temperatureMenu(){
        menu("Temperature",new String[]{"Automatic","Celsius","Fahrenheit"},unitIndex(),choice->{
            prefs.edit().putString("temp_unit",new String[]{"auto","c","f"}[choice]).apply();showPage("settings",false);controlsFocus(widgetsRow+1);});
    }
    private void widgetEditor(){if(homeLayout()!=null){notice("Home widgets",lockedLine());return;}new WidgetEditor();}
    /** Settings → Home widgets. OK turns a widget on or off; Hold OK picks it up, Up and Down move it, OK or Back
     *  puts it down. On the right, miniature pages show how the widgets fill Home, updated with every change. */
    private final class WidgetEditor {
        final ArrayList<String> order=WidgetBoard.order(prefs);
        final HashSet<String> off=new HashSet<>();
        final HashMap<String,Runnable> paints=new HashMap<>();
        final LinearLayout list=new LinearLayout(MainActivity.this);
        final FrameLayout pages=new FrameLayout(MainActivity.this);
        final TextView hint;
        String moving;boolean ignoreUp;
        WidgetEditor(){
            for(String id:prefs.getString("widget_off","").split(","))if(!id.isEmpty())off.add(id);
            FrameLayout o=openOverlay(0xB3000000);
            overlayCancel=()->root.post(()->{showPage("settings",false);controlsFocus(widgetsRow);});
            FrameLayout panel=new FrameLayout(MainActivity.this);panel.setBackground(shape(0xF51C1C1E,26));panel.setElevation(px(24));panel.setClipChildren(false);
            float h=Math.max(440,98+order.size()*61+20); // grows with the number of widgets
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(px(760),px(h));lp.gravity=Gravity.CENTER;o.addView(panel,lp);
            panel.addView(text("Home widgets",24,Color.WHITE,"sans-serif-medium"),pos(420,32,28,26));
            hint=text("",13,0xB3FFFFFF,"sans-serif");hint.setSingleLine(true);panel.addView(hint,pos(420,18,29,64));setHint();
            list.setOrientation(LinearLayout.VERTICAL);list.setClipChildren(false);list.setClipToPadding(false);list.setPadding(px(8),0,px(8),0);
            panel.addView(list,pos(436,order.size()*61,20,98));
            pages.setClipChildren(false);panel.addView(pages,pos(262,370,472,34));
            for(String id:order)list.addView(row(id),rowParams());
            drawPages();
            if(motion()){panel.setScaleX(.96f);panel.setScaleY(.96f);panel.animate().scaleX(1).scaleY(1).setDuration(360).setInterpolator(spring).start();}
            root.post(()->list.getChildAt(0).requestFocus());
        }
        LinearLayout.LayoutParams rowParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,px(54));p.bottomMargin=px(7);return p;}
        final boolean inputs=!InputsWidget.list(MainActivity.this).isEmpty();
        boolean available(String id){return id.equals("house")?house:id.equals("inputs")?inputs:true;}
        void setHint(){hint.setText(moving!=null?"Up and Down move it. OK puts it down.":"OK turns a widget on or off. Hold OK to move it.");}
        GradientDrawable oval(int color){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(color);return d;}
        View row(String id){
            FrameLayout row=new FrameLayout(MainActivity.this);row.setFocusable(true);row.setFocusableInTouchMode(true);row.setSoundEffectsEnabled(false);row.setClipChildren(false);
            row.setContentDescription(WidgetBoard.title(id));
            View disc=new View(MainActivity.this);row.addView(disc,pos(32,32,12,11));
            Glyph glyph=new Glyph(MainActivity.this).kind(WidgetBoard.glyph(id));row.addView(glyph,pos(18,18,19,18));
            TextView name=text(WidgetBoard.title(id),15,Color.WHITE,"sans-serif-medium");row.addView(name,pos(280,19,56,9));
            TextView about=text(available(id)?WidgetBoard.about(id):id.equals("inputs")?"Only on TVs: a TV stick has no HDMI inputs.":"Install House Control TV to add your home.",11.5f,0x99FFFFFF,"sans-serif");
            about.setSingleLine(true);about.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(about,pos(288,15,56,31));
            View track=new View(MainActivity.this),knob=new View(MainActivity.this);row.addView(track,pos(40,24,368,15));row.addView(knob,pos(20,20,370,17));
            TextView grip=text("≡",22,0xFF111827,"sans-serif-medium");grip.setGravity(Gravity.CENTER);grip.setAlpha(0);row.addView(grip,pos(40,24,368,13));
            Runnable paint=()->{
                boolean f=row.hasFocus(),m=id.equals(moving),lit=f||m,on=!off.contains(id)&&available(id);
                row.setBackground(shape(lit?0xFAFFFFFF:0x14FFFFFF,14));
                name.setTextColor(lit?0xFF111827:Color.WHITE);about.setTextColor(lit?0xFF5B6472:0x99FFFFFF);
                disc.setBackground(oval(on?0xFF0A84FF:lit?0x1A000000:0x26FFFFFF));glyph.color(on?Color.WHITE:lit?0xFF111827:0xCCFFFFFF);
                track.setBackground(shape(on?0xFF34C759:lit?0x2E111827:0x33FFFFFF,12));knob.setBackground(shape(Color.WHITE,10));knob.setElevation(px(1.5f));
                float x=on?px(16):0;knob.animate().cancel();if(motion())knob.animate().translationX(x).setDuration(260).setInterpolator(spring).start();else knob.setTranslationX(x);
                track.setAlpha(m?0:1);knob.setAlpha(m?0:1);grip.setAlpha(m?1:0);
                row.setAlpha(available(id)?1:.5f);
            };
            paints.put(id,paint);paint.run();
            row.setOnFocusChangeListener((v,f)->{paint.run();lift(row,f);if(f)navigationSound(list.indexOfChild(row));});
            row.setOnClickListener(v->{
                if(moving!=null)return;sound();
                if(!available(id)){row.animate().cancel();row.setTranslationX(0);if(motion())row.animate().translationX(px(6)).setDuration(70).withEndAction(()->row.animate().translationX(0).setDuration(320).setInterpolator(new OvershootInterpolator(3f)).start()).start();return;}
                if(!off.remove(id))off.add(id);save();paint.run();drawPages();
            });
            row.setOnLongClickListener(v->{sound();moving=id;ignoreUp=true;setHint();paint.run();lift(row,true);return true;});
            row.setOnKeyListener((v,key,e)->{
                if(!id.equals(moving))return false;
                boolean down=e.getAction()==KeyEvent.ACTION_DOWN;
                if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_BACK){if(!down){if(ignoreUp&&key!=KeyEvent.KEYCODE_BACK)ignoreUp=false;else drop(row);}return true;}
                if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(down)move(row,id,key==KeyEvent.KEYCODE_DPAD_UP?-1:1);return true;}
                return key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT;
            });
            return row;
        }
        void lift(View row,boolean f){
            boolean m=moving!=null&&row.hasFocus();float s=m?1.045f:f?1.02f:1;row.animate().cancel();
            if(motion())row.animate().scaleX(s).scaleY(s).setDuration(300).setInterpolator(spring).start();else{row.setScaleX(s);row.setScaleY(s);}
            row.setElevation(px(m?22:f?10:0));row.setTranslationZ(px(m?10:0));
        }
        /** Swaps the picked-up row with its neighbour. The neighbour moves in the list (the focused row stays
         *  attached, so it keeps focus) and both glide from their old places. */
        void move(View row,String id,int d){
            int i=order.indexOf(id),j=i+d;if(j<0||j>=order.size())return;
            View other=list.getChildAt(j);int rowTop=row.getTop(),otherTop=other.getTop();
            Collections.swap(order,i,j);list.removeView(other);list.addView(other,i,rowParams());
            for(View v:new View[]{row,other}){float from=v==row?rowTop-otherTop:otherTop-rowTop;v.animate().cancel();v.setTranslationY(from);
                if(motion())v.animate().translationY(0).setDuration(300).setInterpolator(ease).start();else v.setTranslationY(0);}
            row.animate().scaleX(1.045f).scaleY(1.045f).setDuration(300).start();
            navigationSound(j);save();drawPages();
        }
        void drop(View row){moving=null;sound();setHint();for(Runnable r:paints.values())r.run();lift(row,true);save();}
        void save(){WidgetBoard.save(prefs,order,off);}
        /** Miniature Home pages: the greeting, the widgets of each page, and the apps that stay underneath. */
        void drawPages(){
            pages.removeAllViews();
            pages.addView(text("Your Home",13,0x99FFFFFF,"sans-serif-medium"),pos(240,16,2,0));
            ArrayList<ArrayList<String>> layout=WidgetBoard.pages(WidgetBoard.enabled(prefs),house,inputs);
            if(layout.isEmpty()){TextView t=text("Only the greeting and your apps.",12.5f,0x80FFFFFF,"sans-serif");pages.addView(t,pos(250,18,2,30));return;}
            float mw=122,mh=69,k=(mw-16)/WidgetBoard.ROW_W;
            for(int p=0;p<Math.min(6,layout.size());p++){
                float x=(p%2)*(mw+14),y=28+(p/2)*(mh+30);
                FrameLayout mini=new FrameLayout(MainActivity.this);GradientDrawable bg=shape(0x14FFFFFF,8);bg.setStroke(Math.max(1,px(.8f)),0x26FFFFFF);mini.setBackground(bg);
                pages.addView(mini,pos(mw,mh,x,y));
                View g1=new View(MainActivity.this);g1.setBackground(shape(0x66FFFFFF,1.5f));mini.addView(g1,pos(30,3.5f,8,8));
                View g2=new View(MainActivity.this);g2.setBackground(shape(0x33FFFFFF,1.5f));mini.addView(g2,pos(20,2.5f,8,14));
                ArrayList<String> ids=layout.get(p);
                if(ids.contains("house")){
                    for(int s=0;s<5;s++){View dot=new View(MainActivity.this);dot.setBackground(oval(0x4DFFFFFF));mini.addView(dot,pos(6,6,8+s*8.5f,34));}
                    Glyph g=new Glyph(MainActivity.this).kind("home").color(0xCCFFFFFF);mini.addView(g,pos(26,26,mw-40,14));
                } else {
                    float bx=8;
                    for(String id:ids){float bw=WidgetBoard.width(id)*k;View b=new View(MainActivity.this);b.setBackground(shape(0x33FFFFFF,3));mini.addView(b,pos(bw,24,bx,24));
                        Glyph g=new Glyph(MainActivity.this).kind(WidgetBoard.glyph(id)).color(0xD9FFFFFF);mini.addView(g,pos(12,12,bx+bw/2-6,30));bx+=bw+WidgetBoard.GAP*k;}
                }
                float aw=(mw-16-5*3)/6f;for(int a=0;a<6;a++){View app=new View(MainActivity.this);app.setBackground(shape(0x26FFFFFF,2));mini.addView(app,pos(aw,9,8+a*(aw+3),mh-15));}
                ArrayList<String> names=new ArrayList<>();for(String id:ids)names.add(WidgetBoard.title(id));
                TextView label=text(String.join(" · ",names),11,0xA6FFFFFF,"sans-serif");
                label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);pages.addView(label,pos(mw,15,x+1,y+mh+6));
            }
        }
    }
    private final class CapabilityTv implements Capabilities.Tv {
        final SharedPreferences.Editor edit=prefs.edit();
        /** The panel and its pane as this was built. runAction builds one as the action starts, so a late result knows whether the panel still shows that action. */
        final CapabilityCenter center=capabilityOverlay;
        final int pane=center==null?0:center.paneChanges;
        public boolean clockShown(){return prefs.getBoolean("clock",true);}
        public boolean hasInputs(){return !InputsWidget.list(MainActivity.this).isEmpty();}
        public boolean widgetOn(String id){return WidgetBoard.on(prefs,id);}
        public void showClock(boolean shown){edit.putBoolean("clock",shown);}
        public void showWidget(String id,boolean on){HashSet<String> off=new HashSet<>();for(String value:prefs.getString("widget_off","").split(","))if(!value.isEmpty())off.add(value);if(on)off.remove(id);else off.add(id);edit.putString("widget_off",String.join(",",off));}
        public void remember(String line){edit.putString(Capabilities.LAST,line);}
        public Commands.Registry registry(){return commandRegistry();}
        public boolean openApp(String pkg){App app=appIndex.get(pkg);return app!=null&&launch(app);}
        public void selectTab(String id){showPage(id,false);}
        public Profiles profile(){return profiles;}
        public String homeLayout(){return MainActivity.this.homeLayout();}
        public HouseScenes.Read house(){return house?houseRead:HouseScenes.Read.failed("House Control TV is not installed or is turned off.");}
        public void readHouse(Runnable done){MainActivity.this.readHouse(done);}
        public void runScene(HouseScenes.Scene scene,java.util.function.Supplier<String> refusal,java.util.function.Consumer<HouseScenes.Outcome> done){houseLink.run(scene,refusal,done);}
        public String sceneApp(String scene){return MainActivity.this.sceneApp(scene);}
        public Capabilities.Away away(){return center==null||capabilityOverlay!=center?Capabilities.Away.CLOSED:!center.shows(pane)?Capabilities.Away.MOVED_ON:!started?Capabilities.Away.OFF_SCREEN:null;}
    }
    private Commands.Registry commandRegistry(){
        ArrayList<Commands.Target> targets=new ArrayList<>();
        List<String> home=homeApps();
        for(App app:appIndex.values())if(!app.label.trim().isEmpty())targets.add(new Commands.Target(Commands.Kind.APP,app.pkg,app.label,profiles.allowsApp(app.pkg,home.contains(app.pkg))));
        String[] tabIds={"home","apps","settings"},tabNames={"Home","Apps","Settings"};
        for(int i=0;i<tabIds.length;i++)targets.add(new Commands.Target(Commands.Kind.TAB,tabIds[i],tabNames[i],!tabIds[i].equals("settings")||profiles.allows(Profiles.Content.SETTINGS)));
        boolean inputs=!InputsWidget.list(this).isEmpty();
        for(String id:WidgetBoard.IDS)targets.add(new Commands.Target(Commands.Kind.WIDGET,id,WidgetBoard.title(id),id.equals("house")?house:!id.equals("inputs")||inputs));
        targets.addAll(sceneTargets());
        return new Commands.Registry(targets);
    }
    private ArrayList<Commands.Target> sceneTargets(){
        ArrayList<Commands.Target> targets=new ArrayList<>();
        if(house&&houseRead!=null&&houseRead.home!=null)for(HouseScenes.Scene scene:houseRead.home.scenes)targets.add(new Commands.Target(Commands.Kind.SCENE,scene.id,scene.name,true));
        return targets;
    }
    private final HomePresets.Tv homeTv=new HomePresets.Tv(){
        public boolean hasApp(String pkg){return appIndex.containsKey(pkg);}
        public boolean hasWidget(String id){return id.equals("house")?house:id.equals("inputs")?!InputsWidget.list(MainActivity.this).isEmpty():Arrays.asList(WidgetBoard.IDS).contains(id);}
        public boolean hasScene(String id){return partNames.scene(id)!=null;}
    };
    private final CapabilityPanel.Names partNames=new CapabilityPanel.Names(){
        public String app(String pkg){App app=appIndex.get(pkg);return app==null?null:app.label;}
        public String widget(String id){return WidgetBoard.title(id);}
        public String scene(String id){for(Commands.Target scene:sceneTargets())if(scene.id.equals(id))return scene.name;return null;}
        public String wallpaper(int index){return WALLPAPERS[Math.max(0,Math.min(WALLPAPERS.length-1,index))];}
        public boolean house(){return house;}
    };
    private void capabilityCenter(){new CapabilityCenter(CapabilityPanel.Section.ACTIONS,false,null);}
    private void focusHomeScene(String id){if(houseWidgets!=null)for(HouseWidgets.SceneTile tile:houseWidgets.scenes)if(tile.id.equals(id))tile.requestFocus();}
    private final class CapabilityCenter {
        final boolean dark=prefs.getBoolean("capability_dark",false);
        final int paper=dark?Color.BLACK:Color.WHITE,ink=dark?Color.WHITE:0xFF1D1D1F,soft=dark?0xB3FFFFFF:0xFF5B6472,rest=dark?0x1FFFFFFF:0x0F000000,chosen=dark?0x4DFFFFFF:0x29000000;
        final boolean settings=profiles.allows(Profiles.Content.SETTINGS);
        final List<CapabilityPanel.Section> shown=CapabilityPanel.sections(house,profiles);
        final CapabilityPanel.Health health=new CapabilityPanel.Health();
        final FrameLayout panel,items,pane;
        final LinearLayout sections=new LinearLayout(MainActivity.this),list=new LinearLayout(MainActivity.this);
        final ScrollView scroller=new ScrollView(MainActivity.this);
        final TextView last,theme,paneTitle,paneBody,status,primary,secondary;
        final ArrayList<TextView> sectionRows=new ArrayList<>(),rows=new ArrayList<>();
        final ArrayList<Capabilities.Action> actions=new ArrayList<>();
        CapabilityPanel.Section section;
        HealthSnapshot.Metric selectedMetric=HealthSnapshot.Metric.MEMORY;
        TextView chosenRow,previewButton;
        EditText field;
        Runnable primaryAction,secondaryAction;
        Runnable pending;
        View opener;
        boolean confirming;
        int paneChanges;
        final String homeScene;
        String wanted;
        int houseReads;
        boolean reading;
        CapabilityCenter(CapabilityPanel.Section start,boolean focusTheme,String scene){
            capabilityOverlay=this;homeScene=scene;wanted=scene;
            FrameLayout o=openOverlay(0xB3000000);
            overlayCancel=()->{capabilityOverlay=null;health.close();if(page.equals("settings")){showPage("settings",false);controlsFocus(capabilitiesRow);}
                else{showPage(page,true);if(homeScene!=null)root.post(()->focusHomeScene(homeScene));}};
            panel=new FrameLayout(MainActivity.this);GradientDrawable bg=shape(paper,24);if(dark)bg.setStroke(Math.max(1,px(1)),0x40FFFFFF);panel.setBackground(bg);panel.setElevation(px(24));
            o.addView(panel,new FrameLayout.LayoutParams(px(900),px(480),Gravity.CENTER));
            panel.addView(text(settings?"Capabilities":shown.contains(CapabilityPanel.Section.SCENES)?"Scenes and mode":"Mode",24,ink,"sans-serif-medium"),pos(500,32,28,22));
            last=text(settings?lastLine():"",12.5f,soft,"sans-serif");last.setSingleLine(true);panel.addView(last,pos(840,18,28,58));
            sections.setOrientation(LinearLayout.VERTICAL);panel.addView(sections,pos(170,shown.size()*50,20,92));
            for(int i=0;i<shown.size();i++){final CapabilityPanel.Section s=shown.get(i);TextView row=control(s.label,false);onFocus(row,i,()->select(s));sections.addView(row,rowParams());sectionRows.add(row);}
            theme=control(dark?"Dark mode   On":"Dark mode   Off",false);onFocus(theme,4,()->{});theme.setOnClickListener(v->{sound();toggleTheme();});panel.addView(theme,pos(170,44,20,416));
            theme.setVisibility(settings?View.VISIBLE:View.GONE);
            items=new FrameLayout(MainActivity.this);panel.addView(items,pos(300,370,206,92));
            list.setOrientation(LinearLayout.VERTICAL);
            scroller.setVerticalScrollBarEnabled(false);scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);scroller.addView(list);
            pane=new FrameLayout(MainActivity.this);pane.setBackground(shape(rest,16));panel.addView(pane,pos(358,370,522,92));
            paneTitle=text("",18,ink,"sans-serif-medium");paneTitle.setSingleLine(true);paneTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);pane.addView(paneTitle,pos(318,26,20,18));
            paneBody=text("",13.5f,ink,"sans-serif");paneBody.setLineSpacing(px(4),1);pane.addView(paneBody,pos(318,186,20,56));
            status=text("",13,ink,"sans-serif-medium");status.setMaxLines(2);pane.addView(status,pos(318,40,20,252));
            primary=control("",true);primary.setOnClickListener(v->{sound();primaryAction.run();});pane.addView(primary,pos(150,42,20,306));
            secondary=control("",true);secondary.setOnClickListener(v->{sound();secondaryAction.run();});pane.addView(secondary,pos(150,42,186,306));
            primary.setVisibility(View.GONE);secondary.setVisibility(View.GONE);
            if(shown.contains(CapabilityPanel.Section.SCENES)&&start!=CapabilityPanel.Section.SCENES)loadScenes();
            select(shown.contains(start)?start:shown.get(0));
            final View first=focusTheme?theme:chosenRow;root.post(first::requestFocus);
        }
        LinearLayout.LayoutParams rowParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,px(44));p.bottomMargin=px(6);return p;}
        TextView control(String label,boolean center){
            TextView v=text(label,14,ink,"sans-serif-medium");v.setGravity(center?Gravity.CENTER:Gravity.CENTER_VERTICAL);if(!center)v.setPadding(px(14),0,px(10),0);
            v.setSingleLine(true);v.setEllipsize(android.text.TextUtils.TruncateAt.END);v.setFocusable(true);v.setFocusableInTouchMode(true);v.setSoundEffectsEnabled(false);v.setContentDescription(label);
            v.setOnFocusChangeListener((x,f)->paint(v));paint(v);return v;
        }
        void paint(TextView v){boolean f=v.hasFocus();v.setBackground(shape(f?ink:v==chosenRow?chosen:rest,12));v.setTextColor(f?paper:ink);}
        void onFocus(TextView v,int step,Runnable then){v.setOnFocusChangeListener((x,f)->{paint(v);if(!f)return;dropPending();navigationSound(step);then.run();});}
        void dropPending(){if(confirming)status.setText("Cancelled. Nothing ran.");pending=null;confirming=false;hideButtons();}
        String lastLine(){Capabilities.Result result=Capabilities.Result.parse(prefs.getString(Capabilities.LAST,null));return result==null?"No capability has run on this TV yet.":"Last: "+result.message;}
        void select(CapabilityPanel.Section next){
            TextView was=chosenRow;chosenRow=sectionRows.get(shown.indexOf(next));if(was!=null)paint(was);paint(chosenRow);
            if(next==section){relink();return;}
            if(section==CapabilityPanel.Section.SCENES)wanted=null;
            section=next;dropPending();opener=null;
            refill();relink();
        }
        void refill(){
            items.removeAllViews();list.removeAllViews();rows.clear();actions.clear();field=null;previewButton=null;scroller.scrollTo(0,0);
            switch(section){
                case ACTIONS:items.addView(scroller,new FrameLayout.LayoutParams(-1,-1));fillRows(Capabilities.list(new CapabilityTv()));break;
                case ASK:askControls();break;
                case LAYOUTS:layoutControls();break;
                case SCENES:loadScenes();break;
                case HEALTH:healthControls();break;
                case MODE:modeControls();break;
            }
        }
        void loadScenes(){
            reading=true;final int request=++houseReads;
            if(section==CapabilityPanel.Section.SCENES)drawScenes();
            readHouse(()->{if(capabilityOverlay!=this||request!=houseReads)return;reading=false;if(section==CapabilityPanel.Section.SCENES)drawScenes();});
        }
        void drawScenes(){
            items.removeAllViews();list.removeAllViews();rows.clear();actions.clear();scroller.scrollTo(0,0);
            HouseScenes.Read read=new CapabilityTv().house();
            String note=reading?"Reading scenes from House Control…":read==null?"House Control has not answered yet.":read.problem!=null?read.problem
                :read.home.scenes.isEmpty()?"House Control lists no scenes for this home.":null;
            if(note!=null){
                TextView line=text(note,13,soft,"sans-serif");line.setLineSpacing(px(4),1);items.addView(line,pos(290,80,6,8));
                pane("Scenes",note,"");relink();return;
            }
            items.addView(scroller,new FrameLayout.LayoutParams(-1,-1));
            fillRows(Capabilities.scenes(new CapabilityTv()));relink();
            if(wanted==null)return;
            String id=wanted;wanted=null;
            for(int i=0;i<actions.size();i++)if(actions.get(i).id.equals(id)){rows.get(i).requestFocus();choose(actions.get(i),rows.get(i),false);return;}
            status.setText("That scene is no longer in House Control.");
        }
        void fillRows(List<Capabilities.Action> from){
            actions.addAll(from);
            for(int i=0;i<actions.size();i++){final int index=i;Capabilities.Action action=actions.get(i);TextView row=control(action.name,false);row.setAlpha(action.available()?1:.5f);
                onFocus(row,i,()->showAction(actions.get(index)));row.setOnClickListener(v->{sound();choose(actions.get(index),row,section==CapabilityPanel.Section.SCENES);});list.addView(row,rowParams());rows.add(row);}
            showAction(actions.get(0));
        }
        EditText entry(String hint,int length,Runnable focused,Runnable done){
            EditText f=new EditText(MainActivity.this);f.setSingleLine(true);f.setHint(hint);f.setHintTextColor(soft);f.setTextColor(ink);f.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(15));
            f.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(length)});f.setPadding(px(14),0,px(14),0);
            f.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);f.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            Runnable ring=()->{GradientDrawable b=shape(rest,12);if(f.hasFocus())b.setStroke(Math.max(1,px(1.5f)),ink);f.setBackground(b);};ring.run();
            f.setOnFocusChangeListener((v,on)->{ring.run();if(on){dropPending();navigationSound(0);focused.run();}});
            f.setOnEditorActionListener((v,action,e)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){done.run();return true;}return false;});
            return f;
        }
        void askControls(){
            field=entry("open Plex",80,()->{},this::interpret);
            items.addView(field,pos(300,46,0,0));
            previewButton=control("Preview",true);onFocus(previewButton,1,()->{});previewButton.setOnClickListener(v->{sound();interpret();});items.addView(previewButton,pos(300,42,0,56));
            TextView hint=text("Try open Plex, switch to Apps, show Weather or hide Inputs. OK on the line opens the keyboard.",12.5f,soft,"sans-serif");hint.setLineSpacing(px(4),1);items.addView(hint,pos(296,80,2,114));
            pane("Ask","Type a short request. Luma shows what it understood and asks before anything runs.","");
        }
        void interpret(){
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(),0);
            Commands.Result result=Commands.interpret(field.getText().toString(),commandRegistry());
            if(result.status==Commands.Status.READY){choose(Capabilities.of(result.command,new CapabilityTv()),previewButton,false);return;}
            opener=previewButton;pane("Not run",result.message,"Nothing ran. Edit the request, or cancel it.");
            buttons("Edit",()->field.requestFocus(),"Cancel",()->{field.setText("");field.requestFocus();status.setText("Cancelled. Nothing ran.");});primary.requestFocus();
        }
        void pane(String title,String body,String state){paneChanges++;paneTitle.setText(title);paneBody.setText(body);status.setText(state);}
        boolean shows(int pane){return capabilityOverlay==this&&paneChanges==pane&&primary.hasFocus();}
        void showAction(Capabilities.Action action){pane(action.name,action.available()?action.preview:action.unavailable,action.available()?"Asks before it runs.":"Unavailable");}
        /** A Scenes row keeps the action it was built with. With reread, a refused one reads House Control again and asks with the scene as it is now. */
        void choose(Capabilities.Action action,View from,boolean reread){
            String refused=action.available()?Capabilities.refusal(action,new CapabilityTv()):action.unavailable;
            if(refused!=null&&reread){wanted=action.id;chosenRow.requestFocus();loadScenes();return;}
            if(refused!=null){opener=from;pane(action.name,refused,"Not run. Nothing changed.");return;}
            ask(action.name,action.preview,"Confirm this change.",from,()->runAction(action));
        }
        void ask(String title,String body,String note,View from,Runnable change){
            opener=from;pending=change;confirming=true;pane(title,body,note);
            buttons("Confirm",this::runPending,"Cancel",this::cancel);primary.requestFocus();
        }
        void runPending(){if(pending==null)return;Runnable change=pending;pending=null;confirming=false;change.run();}
        void runAction(Capabilities.Action ran){
            CapabilityTv tv=new CapabilityTv();
            if(ran.capability==Capabilities.Capability.SCENE){buttons("Starting…",()->{},null,null);status.setText("Starting "+ran.label+"…");}
            Capabilities.run(ran,tv,System.currentTimeMillis(),result->{
                tv.edit.apply();
                String line=result.status.label+". "+result.message;
                if(capabilityOverlay==this&&settings)last.setText(lastLine());
                if(!shows(tv.pane)){if(started)Toast.makeText(MainActivity.this,ran.label+". "+line,Toast.LENGTH_LONG).show();return;}
                clock.setVisibility(prefs.getBoolean("clock",true)?View.VISIBLE:View.GONE);
                if(section==CapabilityPanel.Section.ACTIONS)refreshActions();
                buttons("Close",()->closeOverlay(true),null,null);primary.requestFocus();
                pane(ran.name,ran.preview,line);
            });
        }
        void refreshActions(){List<Capabilities.Action> fresh=Capabilities.list(new CapabilityTv());for(int i=0;i<fresh.size()&&i<rows.size();i++){actions.set(i,fresh.get(i));TextView row=rows.get(i);row.setText(fresh.get(i).name);row.setContentDescription(fresh.get(i).name);row.setAlpha(fresh.get(i).available()?1:.5f);}}
        void cancel(){View back=opener;if(back!=null)back.requestFocus();dropPending();status.setText("Cancelled. Nothing ran.");}
        void buttons(String a,Runnable ra,String b,Runnable rb){
            primary.setText(a);primary.setContentDescription(a);primaryAction=ra;primary.setVisibility(View.VISIBLE);
            if(b==null)secondary.setVisibility(View.GONE);else{secondary.setText(b);secondary.setContentDescription(b);secondaryAction=rb;secondary.setVisibility(View.VISIBLE);}
            relink();
        }
        void hideButtons(){primary.setVisibility(View.GONE);secondary.setVisibility(View.GONE);relink();}
        void layoutControls(){
            items.addView(scroller,new FrameLayout.LayoutParams(-1,-1));
            List<HomePresets.Selection> choices=presets.choices();
            for(int i=0;i<choices.size();i++){final HomePresets.Selection choice=choices.get(i);TextView row=control(choice.name()+(choice.equals(presets.active)?"   On Home":""),false);
                onFocus(row,i,()->showLayout(choice));row.setOnClickListener(v->{sound();chooseLayout(choice,row);});list.addView(row,rowParams());rows.add(row);}
            field=entry("Name for this Home",24,this::showSave,this::saveLayout);
            LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,px(46));gap.topMargin=px(8);gap.bottomMargin=px(10);list.addView(field,gap);
            previewButton=control("Save this Home",true);onFocus(previewButton,choices.size()+1,this::showSave);previewButton.setOnClickListener(v->{sound();saveLayout();});list.addView(previewButton,rowParams());
            showLayout(presets.active);
        }
        HomePresets.Proposal preview(HomePresets.Selection choice){return presets.preview(choice,owner(),homeTv);}
        void showLayout(HomePresets.Selection choice){pane(choice.name(),CapabilityPanel.layout(preview(choice),partNames),choice.equals(presets.active)?"On Home now.":"A preview. Home changes only after you confirm.");}
        void showSave(){pane("Save this Home",CapabilityPanel.layout(preview(presets.active),partNames),"Type a name, then Save. Save asks first.");}
        void chooseLayout(HomePresets.Selection choice,View row){
            HomePresets.Proposal proposal=preview(choice);
            if(choice.equals(presets.active)){opener=row;pane(choice.name(),CapabilityPanel.layout(proposal,partNames),"Already on Home. Nothing changed.");return;}
            ask("Apply "+choice.name(),CapabilityPanel.layout(proposal,partNames),choice.equals(HomePresets.Selection.DEFAULT)?"Confirm to bring back your own Home.":"Confirm to change Home. Default stays saved.",row,()->applyLayout(proposal));
        }
        void applyLayout(HomePresets.Proposal proposal){
            String name=proposal.source.name();HomePresets next=presets.apply(proposal);
            if(!profiles.allows(Profiles.Content.SETTINGS)){pane(name,profiles.mode.name+" mode does not change layouts.","Not run. Nothing changed.");return;}
            if(!next.active.equals(proposal.source)){pane(name,"This saved layout is gone.","Not run. Nothing changed.");return;}
            presets=next;prefs.edit().putString(HomePresets.KEY,presets.line()).apply();homePage=0;
            refill();opener=rows.get(presets.choices().indexOf(proposal.source));
            pane(name+" is on Home",CapabilityPanel.layout(proposal,partNames),"Done. Close to see Home.");
            buttons("Close",()->closeOverlay(true),null,null);primary.requestFocus();
        }
        void saveLayout(){
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(),0);
            String name=HomePresets.name(field.getText().toString());HomePresets.Proposal now=preview(presets.active);
            HomePresets next=name==null?null:presets.save(name,now.layout);
            if(next==null){opener=previewButton;pane("Not saved",name==null?"Choose another name. A name cannot be blank, and Default, Movies, Sports, News and Guests are taken.":"Luma cannot store a part of this Home.","Nothing changed.");return;}
            boolean replaces=false;for(HomePresets.Selection choice:presets.choices())replaces|=choice.name().equals(name);
            String note=!next.current(owner()).equals(now.layout)?"This replaces "+name+", which is on Home, so Home changes at once.":replaces?"This replaces the saved "+name+".":"Adds "+name+" to Layouts. Home stays as it is.";
            ask("Save "+name,CapabilityPanel.layout(now,partNames),note,previewButton,()->storeLayout(name,now.layout));
        }
        void storeLayout(String name,HomePresets.Layout layout){
            HomePresets next=presets.save(name,layout);
            if(!profiles.allows(Profiles.Content.SETTINGS)||next==null){pane("Not saved",profiles.mode.name+" mode does not save layouts.","Nothing changed.");return;}
            presets=next;prefs.edit().putString(HomePresets.KEY,presets.line()).apply();
            refill();List<HomePresets.Selection> choices=presets.choices();
            for(int i=0;i<choices.size();i++)if(choices.get(i).name().equals(name))rows.get(i).requestFocus();
            status.setText("Saved "+name+". OK applies it.");
        }
        void modeControls(){
            items.addView(scroller,new FrameLayout.LayoutParams(-1,-1));
            for(Profiles.Mode mode:Profiles.Mode.values()){TextView row=control(mode.name+" mode"+(mode==profiles.mode?"   On":""),false);
                onFocus(row,rows.size(),()->showMode(mode));row.setOnClickListener(v->{sound();chooseMode(mode,row);});list.addView(row,rowParams());rows.add(row);}
            if(profiles.mode==Profiles.Mode.NORMAL){
                TextView caption=text("Child apps",12.5f,soft,"sans-serif-medium");caption.setGravity(Gravity.CENTER_VERTICAL);caption.setPadding(px(14),0,0,0);list.addView(caption,new LinearLayout.LayoutParams(-1,px(32)));
                for(App app:visibleApps(true)){TextView row=control(approval(app),false);onFocus(row,rows.size(),()->showApproval(app));row.setOnClickListener(v->{sound();toggleApproval(app,row);});list.addView(row,rowParams());rows.add(row);}
            }
            showMode(profiles.mode);
        }
        String approval(App app){return (profiles.childApps.contains(app.pkg)?"✓  ":"")+app.label;}
        List<String> approved(){ArrayList<String> names=new ArrayList<>();for(String pkg:profiles.childApps)names.add(appLabel(pkg));return names;}
        void showMode(Profiles.Mode mode){pane(mode.name+" mode",CapabilityPanel.mode(mode,approved()),mode==profiles.mode?"On now.":mode==Profiles.Mode.NORMAL?"OK returns to Normal at once.":"OK asks before it switches.");}
        void showApproval(App app){boolean on=profiles.childApps.contains(app.pkg);pane("Child apps",CapabilityPanel.mode(Profiles.Mode.CHILD,approved()),(on?"Approved. OK removes ":"Not approved. OK adds ")+app.label+".");}
        void toggleApproval(App app,TextView row){
            ArrayList<String> next=new ArrayList<>(profiles.childApps);if(!next.remove(app.pkg))next.add(app.pkg);
            Profiles changed=profiles.approve(next);
            if(changed==profiles){pane("Child apps","Only Normal mode changes the Child apps.","Nothing changed.");return;}
            profiles=changed;prefs.edit().putString(Profiles.KEY,profiles.line()).apply();
            String label=approval(app);row.setText(label);row.setContentDescription(label);showApproval(app);
        }
        void chooseMode(Profiles.Mode mode,View row){
            if(mode==profiles.mode){opener=row;pane(mode.name+" mode",CapabilityPanel.mode(mode,approved()),"Already on. Nothing changed.");return;}
            if(mode==Profiles.Mode.NORMAL){enterMode(mode);return;}
            ask(mode.name+" mode",CapabilityPanel.mode(mode,approved()),"Confirm to switch. Home changes at once.",row,()->enterMode(mode));
        }
        void enterMode(Profiles.Mode mode){
            profiles=profiles.enter(mode);prefs.edit().putString(Profiles.KEY,profiles.line()).apply();homePage=0;
            capabilityOverlay=null;health.close();overlayCancel=null;overlayReturn=modeChip;closeOverlay(false);showPage("home",false);
        }
        void healthControls(){
            items.addView(scroller,new FrameLayout.LayoutParams(-1,-1));
            for(HealthSnapshot.Metric metric:HealthSnapshot.Metric.values()){
                TextView row=control(metric.label,false);
                onFocus(row,metric.ordinal(),()->{selectedMetric=metric;drawHealth();});
                row.setOnClickListener(v->{sound();primary.requestFocus();});
                list.addView(row,rowParams());rows.add(row);
            }
            if(health.shown()==null&&!health.stale())measure();else drawHealth();
        }
        void measure(){
            final int request=health.refresh();drawHealth();final Context app=getApplicationContext();
            healthReads.execute(()->{HealthSnapshot snapshot=HealthReader.read(app);handler.post(()->{if(health.accept(request,snapshot)&&section==CapabilityPanel.Section.HEALTH)drawHealth();});});
        }
        void drawHealth(){
            HealthSnapshot snapshot=health.shown();SimpleDateFormat time=new SimpleDateFormat("HH:mm:ss",Locale.getDefault());
            for(int i=0;i<rows.size();i++){
                String state=snapshot==null?"Measuring":health.stale()?"Stale":CapabilityPanel.status(snapshot.readings.get(i).status);
                String label=HealthSnapshot.Metric.values()[i].label+"   "+state;
                rows.get(i).setText(label);rows.get(i).setContentDescription(label);
            }
            opener=rows.get(selectedMetric.ordinal());
            if(snapshot==null)pane(selectedMetric.label,"Waiting for Android's measurement.","Measuring…");
            else{
                HealthSnapshot.Reading reading=snapshot.readings.get(selectedMetric.ordinal());
                String[] detail=CapabilityPanel.row(reading,time.format(new Date(reading.at)),health.stale());
                pane(detail[0],detail[1],health.stale()?"Refreshing. These values are stale.":"");
            }
            buttons("Refresh",this::measure,"Back",()->chosenRow.requestFocus());
        }
        void toggleTheme(){prefs.edit().putBoolean("capability_dark",!dark).apply();health.close();new CapabilityCenter(section,true,null);}
        boolean back(){
            if(confirming){cancel();return true;}
            View focused=getCurrentFocus();
            if(focused==null||sectionRows.contains(focused)||focused==theme)return false;
            chosenRow.requestFocus();return true;
        }
        ArrayList<View> column(){ArrayList<View> column=new ArrayList<>(rows);if(field!=null){column.add(field);column.add(previewButton);}return column;}
        void relink(){
            ArrayList<View> column=column();View entry=column.isEmpty()?null:column.get(0);
            for(int i=0;i<sectionRows.size();i++){View row=sectionRows.get(i);link(row,row,entry==null?row:entry,i==0?row:sectionRows.get(i-1),i+1<sectionRows.size()?sectionRows.get(i+1):settings?theme:row);}
            link(theme,theme,entry==null?theme:entry,sectionRows.get(sectionRows.size()-1),theme);
            boolean buttonsShown=primary.getVisibility()==View.VISIBLE;
            for(int i=0;i<column.size();i++){View v=column.get(i);link(v,chosenRow,buttonsShown?primary:v,i==0?v:column.get(i-1),i+1<column.size()?column.get(i+1):v);}
            View back=opener!=null?opener:column.isEmpty()?chosenRow:column.get(0);
            link(primary,back,secondary.getVisibility()==View.VISIBLE?secondary:primary,primary,primary);
            link(secondary,primary,secondary,secondary,secondary);
        }
        void link(View v,View left,View right,View up,View down){v.setNextFocusLeftId(id(left));v.setNextFocusRightId(id(right));v.setNextFocusUpId(id(up));v.setNextFocusDownId(id(down));}
        int id(View v){if(v.getId()==View.NO_ID)v.setId(View.generateViewId());return v.getId();}
    }
    @SuppressWarnings("unchecked") private void controlsFocus(int i) { root.post(()-> { Object tag=content.getTag(); if(tag instanceof ArrayList) ((ArrayList<View>)tag).get(i).requestFocus(); }); }
    /** A settings row: either a value with a chevron, or an Apple-style switch bound to a preference. */
    private View option(LinearLayout parent,String title,java.util.function.Supplier<String> value,String toggle,Runnable action,Runnable onFocus) {
        FrameLayout row=new FrameLayout(this);row.setFocusable(true);row.setFocusableInTouchMode(true);row.setSoundEffectsEnabled(false);
        TextView name=text(title,15,Color.WHITE,"sans-serif-medium");row.addView(name,pos(300,20,18,12));
        TextView detail=value==null?null:text(value.get()+(value.get().equals("")?"›":"   ›"),14,0x99FFFFFF,"sans-serif");
        if(detail!=null){detail.setGravity(Gravity.RIGHT);row.addView(detail,pos(200,20,258,12));}
        final View track=new View(this),knob=new View(this);
        if(toggle!=null){row.addView(track,pos(40,24,418,9));row.addView(knob,pos(20,20,420,11));}
        Runnable paint=()->{boolean f=row.hasFocus();row.setBackground(shape(f?0xFAFFFFFF:light?0x8CFFFFFF:0x14FFFFFF,14));name.setTextColor(f?0xFF111827:ink(0xFF));
            if(detail!=null)detail.setTextColor(f?0xFF5B6472:ink(0x99));
            if(toggle!=null){boolean on=toggle.equals("motion")?motion():prefs.getBoolean(toggle,true);track.setBackground(shape(on?0xFF34C759:f||light?0x2E111827:0x33FFFFFF,12));knob.setBackground(shape(Color.WHITE,10));knob.setElevation(px(1.5f));
                float x=on?px(16):0;knob.animate().cancel();if(motion())knob.animate().translationX(x).setDuration(260).setInterpolator(spring).start();else knob.setTranslationX(x);}};
        paint.run();
        row.setOnFocusChangeListener((v,f)->{paint.run();row.animate().cancel();if(motion())row.animate().scaleX(f?1.02f:1).scaleY(f?1.02f:1).setDuration(300).setInterpolator(spring).start();else{row.setScaleX(1);row.setScaleY(1);}row.setElevation(px(f?10:0));if(f){onFocus.run();navigationSound(parent.indexOfChild(row));reveal(row);}});
        row.setOnClickListener(v->{sound();
            if(toggle!=null){boolean on=toggle.equals("motion")?motion():prefs.getBoolean(toggle,true);prefs.edit().putBoolean(toggle,!on).apply();
                if(toggle.equals("clock"))clock.setVisibility(!on?View.VISIBLE:View.GONE);paint.run();}
            action.run();});
        row.setContentDescription(title);
        row.setOnKeyListener((v,key,e)-> {if(e.getAction()==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_UP && parent.getChildAt(1)==row) {tabs.get(2).requestFocus();return true;}return false;});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,px(44));lp.bottomMargin=px(6);parent.addView(row,lp);return row;
    }
    @Override public void onBackPressed() {
        if (overlay!=null) { if(capabilityOverlay!=null&&capabilityOverlay.back())return; closeOverlay(true); return; }
        if (!page.equals("home")) showPage("home",true);
        else if (board!=null && board.index>0) {board.showPage(0);tabs.get(0).requestFocus();}
        else if (scroll!=null && scroll.getScrollY()>0) {scroll.smoothScrollTo(0,0);if(!tiles.isEmpty())tiles.get(0).requestFocus();}
        else tabs.get(0).requestFocus();
    }

    private final class Tile extends FrameLayout {
        final App app;
        final Art art;
        final FrameLayout face;
        final Shine light;
        final GradientDrawable rim;
        final TextView label;
        final int index;
        final TileMotion movement;
        Tile(App item,int number) {
            super(MainActivity.this);app=item;index=number;
            setClipChildren(false);setClipToPadding(false);setFocusable(true);setFocusableInTouchMode(true);setSoundEffectsEnabled(false);
            setContentDescription(app.label+(profiles.allows(Profiles.Content.SETTINGS)?". OK: open. Hold OK: organize app.":". OK: open."));
            face=new FrameLayout(MainActivity.this);GradientDrawable faceBg=shape(app.color,16);faceBg.setStroke(Math.max(1,px(.6f)),0x1FFFFFFF);face.setBackground(faceBg);face.setClipToOutline(true);face.setElevation(px(2));face.setCameraDistance(px(900));
            art=new Art(app);face.addView(art,new FrameLayout.LayoutParams(-1,-1));
            light=new Shine();light.setAlpha(0);face.addView(light,new FrameLayout.LayoutParams(-1,-1));
            rim=shape(Color.TRANSPARENT,16);rim.setStroke(px(1.2f),0xF0FFFFFF);rim.setAlpha(0);face.setForeground(rim);
            addView(face,pos(132,76,0,0));
            label=text(app.label,13.5f,ink(0xFF),"sans-serif-medium");label.setGravity(Gravity.CENTER);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setAlpha(0);shadow(label,6);
            addView(label,pos(160,22,-14,88));
            movement=new TileMotion(this);
            setOnFocusChangeListener((v,f)-> {
                movement.focus(f);
                face.setElevation(px(f?14:2));rim.setAlpha(f?210:0);
                label.animate().cancel();if(motion())label.animate().alpha(f?1:0).translationY(f?0:-px(3)).setDuration(f?260:140).setInterpolator(ease).start();else label.setAlpha(f?1:0);
                setTranslationZ(px(f?20:0));
                if(f) {
                    if(page.equals("home")&&index<6)homeReturnPackage=app.pkg;
                    selectApp(app);wallpaper.follow(index);
                    if(index<6) scroll.post(()->scroll.smoothScrollTo(0,0));
                    else requestRectangleOnScreen(new Rect(-px(12),-px(14),getWidth()+px(12),getHeight()+px(5)),false);
                }
            });
            setOnClickListener(v->launch(app));
            // App menus are settings: outside Normal, holding OK just opens the app.
            setOnLongClickListener(v->{if(!profiles.allows(Profiles.Content.SETTINGS))return false;sound();appMenu(app);return true;});
            setOnKeyListener((v,key,e)-> {
                if(key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER) {
                    movement.press(e.getAction()==KeyEvent.ACTION_DOWN);return false;
                }
                if(e.getAction()!=KeyEvent.ACTION_DOWN) return false;
                if(key==KeyEvent.KEYCODE_DPAD_UP && index<6){
                    int[] l=new int[2];getLocationOnScreen(l);float x=l[0]+getWidth()/2f;
                    View above=!page.equals("home")||board==null?null:board.onHouse()?null:board.nearest(x);
                    if(page.equals("home")&&board!=null&&board.onHouse()&&houseWidgets!=null)houseWidgets.focusNearest(x);
                    else if(above!=null)above.requestFocus();else tabs.get(pageTab()).requestFocus();
                    return true;}
                int next=-1;
                if(key==KeyEvent.KEYCODE_DPAD_LEFT && index%6>0) next=index-1;
                if(key==KeyEvent.KEYCODE_DPAD_RIGHT && index%6<5 && index+1<tiles.size()) next=index+1;
                if(key==KeyEvent.KEYCODE_DPAD_DOWN && index+6<tiles.size()) next=index+6;
                if(key==KeyEvent.KEYCODE_DPAD_UP && index>=6) next=index-6;
                if(next>=0){if(e.getRepeatCount()==0 || e.getRepeatCount()%2==0){homeGreeting=false;lastDirectionX=Integer.compare(next%6,index%6);lastDirectionY=Integer.compare(next/6,index/6);navigationSound(next);tiles.get(next).requestFocus();}return true;}
                return key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT || key==KeyEvent.KEYCODE_DPAD_DOWN;
            });
        }
        @Override protected void onDetachedFromWindow(){movement.stop();super.onDetachedFromWindow();}
    }

    /** Native property animations keep the static card artwork cached on the GPU. */
    private final class TileMotion {
        final Tile tile;
        boolean focused,pressed;
        TileMotion(Tile tile){this.tile=tile;}
        void focus(boolean value) {
            focused=value;pressed=false;
            if(value && motion()) {
                tile.face.animate().cancel();
                tile.face.setRotationY(-lastDirectionX*4.5f);tile.face.setRotationX(lastDirectionY*3f);
            }
            start();
            if(motion()) {
                tile.art.animate().cancel();
                float depthX=focused?px((tile.index%6-2.5f)*.7f):0;
                float depthY=focused?-px(1):0;
                if(focused){tile.art.setTranslationX(depthX-px(lastDirectionX*4.5f));tile.art.setTranslationY(depthY+px(lastDirectionY*3));tile.light.setTranslationX(px(lastDirectionX*12));}
                tile.art.animate().translationX(depthX).translationY(depthY).setDuration(520).setInterpolator(drift).start();
                tile.light.animate().translationX(focused?-depthX*2:0).alpha(focused?.6f:0).setDuration(580).setInterpolator(drift).start();
            }
        }
        void press(boolean down){pressed=down;start();}
        void start(){
            if(!motion()){snap();return;}
            tile.face.animate().cancel();tile.label.animate().cancel();tile.light.animate().cancel();
            float scale=(focused?1.11f:1)-(pressed?.03f:0);
            tile.face.animate().scaleX(scale).scaleY(scale).translationY(focused?-px(3):0).rotationX(0).rotationY(0).setDuration(pressed?65:420).setInterpolator(pressed?ease:spring).withLayer().start();
            tile.label.animate().scaleX(focused?1.025f:1).scaleY(focused?1.025f:1).setDuration(260).setInterpolator(ease).start();
            tile.light.animate().alpha(focused?.6f:0).setDuration(200).setInterpolator(ease).start();
        }
        void stop(){tile.face.animate().cancel();tile.art.animate().cancel();tile.label.animate().cancel();tile.light.animate().cancel();}
        void snap(){
            stop();pressed=false;
            float scale=focused?1.11f:1;
            tile.face.setScaleX(scale);tile.face.setScaleY(scale);tile.face.setTranslationY(focused?-px(3):0);tile.face.setRotationX(0);tile.face.setRotationY(0);
            tile.art.setTranslationX(0);tile.art.setTranslationY(0);tile.light.setTranslationX(0);tile.light.setAlpha(focused?.6f:0);
            tile.label.setScaleX(focused?1.025f:1);tile.label.setScaleY(focused?1.025f:1);
        }
    }

    private final class Art extends View {
        final App app; final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        final RectF bounds=new RectF();Shader finish;
        Art(App app) {super(MainActivity.this);this.app=app;}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
            finish=new LinearGradient(0,0,0,h,new int[]{0x20FFFFFF,0x00FFFFFF,0x14000000},new float[]{0,.46f,1},Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas c) {
            float w=getWidth(),h=getHeight();
            bounds.set(0,0,w,h);
            p.setColor(app.color);p.setShader(null);c.drawRoundRect(bounds,px(16),px(16),p);
            String pkg=app.pkg;
            if(pkg.equals("com.google.android.youtube.tv")) {
                float cx=w/2,cy=h/2;
                p.setColor(0xFFFF0033);bounds.set(cx-px(25),cy-px(17),cx+px(25),cy+px(17));c.drawRoundRect(bounds,px(10),px(10),p);
                p.setColor(Color.WHITE);Path path=new Path();path.moveTo(cx-px(5),cy-px(9));path.lineTo(cx+px(10),cy);path.lineTo(cx-px(5),cy+px(9));path.close();c.drawPath(path,p);
            } else if(pkg.equals("com.netflix.ninja")) {
                p.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD));p.setTextSize(px(54));p.setColor(0xFFE50914);p.setTextAlign(Paint.Align.CENTER);c.drawText("N",w/2,h/2+px(19),p);
            } else if(pkg.equals("com.soundcloud.android")) {
                drawIcon(c,app.icon,48);
            } else if(pkg.equals("com.ted.android.tv")) {
                p.setTypeface(Typeface.create("sans-serif-black",Typeface.BOLD));p.setTextSize(px(33));p.setTextAlign(Paint.Align.CENTER);p.setColor(0xFFFF302B);c.drawText("TED",w/2,h/2+px(12),p);
            } else if(pkg.equals("stream.tv.online")) {
                float cx=w/2,cy=h/2-px(2);
                p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(px(2.5f));
                bounds.set(cx-px(27),cy-px(18),cx+px(27),cy+px(18));c.drawRoundRect(bounds,px(6),px(6),p);
                c.drawLine(cx,cy+px(18),cx,cy+px(25),p);c.drawLine(cx-px(10),cy+px(25),cx+px(10),cy+px(25),p);
                p.setStyle(Paint.Style.FILL);Path play=new Path();play.moveTo(cx-px(5),cy-px(9));play.lineTo(cx+px(10),cy);play.lineTo(cx-px(5),cy+px(9));play.close();c.drawPath(play,p);
            } else if(pkg.equals("com.phlox.tvwebbrowser")) {
                float cx=w/2,cy=h/2,r=px(26);
                p.setColor(0xF4FFFFFF);c.drawCircle(cx,cy,r,p);
                p.setColor(0xFFB4C6D6);p.setStrokeWidth(px(1));
                for(int tick=0;tick<24;tick++) {
                    double a=tick*Math.PI/12;float inner=r-px(tick%3==0?6:3);
                    c.drawLine(cx+(float)Math.cos(a)*inner,cy+(float)Math.sin(a)*inner,cx+(float)Math.cos(a)*(r-px(2)),cy+(float)Math.sin(a)*(r-px(2)),p);
                }
                c.save();c.rotate(38,cx,cy);
                Path needle=new Path();needle.moveTo(cx,cy-px(21));needle.lineTo(cx+px(5),cy);needle.lineTo(cx-px(5),cy);needle.close();p.setColor(0xFFE65E62);c.drawPath(needle,p);
                needle.reset();needle.moveTo(cx,cy+px(21));needle.lineTo(cx+px(5),cy);needle.lineTo(cx-px(5),cy);needle.close();p.setColor(0xFF42677F);c.drawPath(needle,p);c.restore();
            } else if(pkg.equals("com.google.android.vending") || pkg.equals("com.android.vending")) {
                drawIcon(c,app.icon,47);
            } else if(app.banner!=null) {
                float ratio=Math.min(w/app.banner.getWidth(),h/app.banner.getHeight());
                float bw=app.banner.getWidth()*ratio,bh=app.banner.getHeight()*ratio;
                bounds.set((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2);p.setAlpha(255);c.drawBitmap(app.banner,null,bounds,p);
            } else drawIcon(c,app.icon,48);
            p.setShader(finish);
            bounds.set(0,0,w,h);c.drawRoundRect(bounds,px(16),px(16),p);p.setShader(null);
        }
        private void drawIcon(Canvas c,Bitmap icon,float size) {float s=px(size);bounds.set((getWidth()-s)/2,(getHeight()-s)/2,(getWidth()+s)/2,(getHeight()+s)/2);p.setAlpha(255);c.drawBitmap(icon,null,bounds,p);}
    }

    /** A separate compositor layer keeps the icon display list static during focus. */
    private final class Shine extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Shine(){super(MainActivity.this);}
        @Override public boolean hasOverlappingRendering(){return false;}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            paint.setShader(new LinearGradient(-w*.4f,0,w*.95f,h,new int[]{0x00FFFFFF,0x40FFFFFF,0x00FFFFFF},new float[]{0,.5f,1},Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas canvas){canvas.drawRect(0,0,getWidth(),getHeight(),paint);}
    }

    /** Independent compositor surfaces: distant clouds drift behind the cut-out landscape. */
    private final class Wallpaper extends FrameLayout {
        final HandlerThread surfaceThread=new HandlerThread("Luma scenery",android.os.Process.THREAD_PRIORITY_BACKGROUND);
        final Handler surfaceHandler;
        final SceneLayer sky, terrain;
        final FrameLayout skyTrack;
        volatile boolean ambientRunning;
        volatile float ambientSeconds,cloudX,cloudY;
        long lastAmbientTick,ambientTicks;
        private final Runnable ambientTick=new Runnable(){public void run(){
            if(!ambientEligible()){syncAmbient();return;}
            long now=SystemClock.uptimeMillis();
            ambientSeconds+=Math.min(.25f,(now-lastAmbientTick)/1000f);lastAmbientTick=now;
            cloudX=px(14)*(float)Math.sin(ambientSeconds*Math.PI*2/112);
            cloudY=px(.65f)*(float)Math.sin(ambientSeconds*Math.PI*2/83);
            if(blend>=1){sky.drawSurface();terrain.drawSurface();ambientTicks++;}
            handler.postDelayed(this,100);
        }};
        Bitmap skySource, terrainSource;
        int requestedStyle=-1;
        volatile float blend=1;
        ValueAnimator sceneTransition;
        Wallpaper() {
            super(MainActivity.this);
            surfaceThread.start();surfaceHandler=new Handler(surfaceThread.getLooper());
            setClipChildren(false);setClipToPadding(false);
            sky=new SceneLayer(false);terrain=new SceneLayer(true);
            skyTrack=new FrameLayout(MainActivity.this);skyTrack.setClipChildren(false);skyTrack.setClipToPadding(false);
            skyTrack.addView(sky,new FrameLayout.LayoutParams(-1,-1));
            int margin=px(30);
            int width=getResources().getDisplayMetrics().widthPixels+margin*2;
            int height=getResources().getDisplayMetrics().heightPixels+margin*2;
            FrameLayout.LayoutParams back=new FrameLayout.LayoutParams(width,px(220)+margin);
            back.leftMargin=-margin;back.topMargin=-margin;addView(skyTrack,back);
            FrameLayout.LayoutParams front=new FrameLayout.LayoutParams(width,height);
            front.leftMargin=-margin;front.topMargin=-margin;addView(terrain,front);
            update();
        }
        void follow(int index) {
            skyTrack.animate().cancel();terrain.animate().cancel();
            float position=(index%6-2.5f)/2.5f;
            float arc=1-position*position;
            if(motion()) {
                skyTrack.animate().translationX(-position*px(requestedStyle>=3?11:5)).translationY(px(arc*(requestedStyle>=3?2.2f:.7f))).setDuration(1000).setInterpolator(drift).start();
                terrain.animate().translationX(-position*px(22)).translationY(px(arc*3-Math.min(index/6,2)*2)).setDuration(680).setInterpolator(drift).start();
            } else rest();
        }
        void rest(){
            skyTrack.animate().cancel();terrain.animate().cancel();
            skyTrack.setTranslationX(0);skyTrack.setTranslationY(0);terrain.setTranslationX(0);terrain.setTranslationY(0);
        }
        private boolean ambientEligible() {
            return started && hasWindowFocus() && !page.equals("settings") && requestedStyle!=2
                && motion() && prefs.getBoolean("ambient",true) && sky.scene!=null && terrain.scene!=null;
        }
        void syncAmbient() {
            if(ambientEligible()) {
                if(!ambientRunning){ambientRunning=true;lastAmbientTick=SystemClock.uptimeMillis();handler.postDelayed(ambientTick,100);}
            } else {
                pauseAmbient();
                if(!motion() || !prefs.getBoolean("ambient",true) || requestedStyle==2) {
                    cloudX=0;cloudY=0;sky.drawSurface();terrain.drawSurface();
                }
            }
        }
        void pauseAmbient(){ambientRunning=false;handler.removeCallbacks(ambientTick);}
        private Bitmap compose(Bitmap source,int style,boolean foreground,int width,int height) {
            Bitmap result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(result);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            if(!foreground || style==2)c.drawColor(base(style));
            if(source!=null && style!=2) {
                // Shared destination bounds keep the horizon registered; only the terrain has alpha.
                // Normalize almost-opaque generated terrain pixels, preserving antialiased skyline edges.
                // Otherwise a moving sky faintly shines through the mountain interiors.
                if(foreground)paint.setColorFilter(new ColorMatrixColorFilter(new float[]{1,0,0,0,0, 0,1,0,0,0, 0,0,1,0,0, 0,0,0,1.04f,0}));
                c.drawBitmap(source,null,new RectF(0,0,width,height),paint);paint.setColorFilter(null);
                if(foreground)paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
                if(style==1){paint.setColor(0x504B3159);c.drawRect(0,0,width,height,paint);paint.setAlpha(255);}
            }
            if(foreground)paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
            if(pale(style)) {
                // A white veil behind the greeting and the favourites keeps dark text crisp on the pale silk.
                paint.setShader(new LinearGradient(0,0,width*.75f,0,new int[]{0x8CF6F6F8,0x33F6F6F8,0x00F6F6F8},new float[]{0,.5f,1},Shader.TileMode.CLAMP));c.drawRect(0,0,width,height,paint);
                paint.setShader(new LinearGradient(0,0,0,height,new int[]{0x00F2F2F5,0x00F2F2F5,0x59F2F2F5},new float[]{0,.55f,1},Shader.TileMode.CLAMP));c.drawRect(0,0,width,height,paint);
            } else {
                // Silver's night picture is neutral graphite: its shading is black, not the blue of the older scenes.
                int tint=style>=SILVER?0x000000:0x080D18;
                paint.setShader(new LinearGradient(0,0,width*.88f,0,new int[]{0xA0000000|tint,0x30000000|tint,tint},new float[]{0,.5f,1},Shader.TileMode.CLAMP));c.drawRect(0,0,width,height,paint);
                paint.setShader(new LinearGradient(0,0,0,height,new int[]{0x40000000|tint,0x08000000|tint,0x88000000|tint,(style>=SILVER?0xC8000000:0xE8000000)|tint},new float[]{0,.3f,.68f,1},Shader.TileMode.CLAMP));c.drawRect(0,0,width,height,paint);
            }
            return result;
        }
        void update(){show(wallpaperStyle());}
        /** Fill under the picture: pale silver, graphite, or the night blue of the original scenes. */
        int base(int style){return pale(style)?0xFFE4E5E8:style>=SILVER?0xFF0B0B0D:0xFF080C14;}
        /** Shows a wallpaper without saving it (the picker previews live). Silver resolves to its day or night picture. */
        void show(final int choice) {
            final int style=variant(choice);
            if(requestedStyle==style)return;
            requestedStyle=style;
            final int width=getResources().getDisplayMetrics().widthPixels;
            final int height=getResources().getDisplayMetrics().heightPixels;
            loader.execute(()-> {
                Bitmap back,front;
                if(style>=3) {
                    // Photographic and rendered wallpapers: one full-height layer, no cut-out terrain.
                    BitmapFactory.Options full=new BitmapFactory.Options();full.inPreferredConfig=Bitmap.Config.ARGB_8888;
                    Bitmap photo=BitmapFactory.decodeResource(getResources(),PHOTOS[Math.min(PHOTOS.length-1,style-3)],full);
                    back=compose(photo,style,false,width,height);photo.recycle();
                    front=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
                } else {
                    if(skySource==null) {
                        BitmapFactory.Options rgb=new BitmapFactory.Options();rgb.inPreferredConfig=Bitmap.Config.RGB_565;
                        skySource=BitmapFactory.decodeResource(getResources(),R.drawable.fjord_sky,rgb);
                        terrainSource=BitmapFactory.decodeResource(getResources(),R.drawable.fjord_terrain);
                    }
                    back=compose(skySource,style,false,width,height);
                    front=compose(terrainSource,style,true,width,height);
                }
                runOnUiThread(()->{
                    if(isDestroyed() || requestedStyle!=style){back.recycle();front.recycle();return;}
                    // Photos and renders have no cut-out terrain: drop its full-screen surface (about 28 MB of buffers).
                    terrain.setVisibility(style>=3?View.GONE:View.VISIBLE);
                    FrameLayout.LayoutParams track=(FrameLayout.LayoutParams)skyTrack.getLayoutParams();int depth=style>=3?getResources().getDisplayMetrics().heightPixels+px(60):px(220)+px(30);
                    if(track.height!=depth){track.height=depth;skyTrack.setLayoutParams(track);}
                    if(sceneTransition!=null)sceneTransition.cancel();
                    sky.previous=sky.scene;terrain.previous=terrain.scene;sky.scene=back;terrain.scene=front;
                    if(sky.previous!=null && motion()) {
                        sceneTransition=ValueAnimator.ofFloat(0,1);sceneTransition.setDuration(420);sceneTransition.setInterpolator(ease);
                        sceneTransition.addUpdateListener(a->{blend=(float)a.getAnimatedValue();sky.drawSurface();terrain.drawSurface();});
                        sceneTransition.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){sky.previous=null;terrain.previous=null;blend=1;sky.drawSurface();terrain.drawSurface();}});
                        sceneTransition.start();
                    } else {sky.previous=null;terrain.previous=null;blend=1;sky.drawSurface();terrain.drawSurface();}
                    syncAmbient();
                });
            });
        }
        private final class SceneLayer extends SurfaceView implements SurfaceHolder.Callback {
            final boolean foreground;
            volatile Bitmap scene,previous;
            final java.util.concurrent.atomic.AtomicBoolean drawPending=new java.util.concurrent.atomic.AtomicBoolean();
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            final RectF bounds=new RectF();
            WaterMesh water;
            int waterWidth,waterHeight;
            SceneLayer(boolean front) {
                super(MainActivity.this);foreground=front;
                if(front){setZOrderMediaOverlay(true);getHolder().setFormat(PixelFormat.TRANSLUCENT);}
                getHolder().addCallback(this);
            }
            @Override public void surfaceCreated(SurfaceHolder holder){drawSurface();}
            @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){drawSurface();}
            @Override public void surfaceDestroyed(SurfaceHolder holder){animate().cancel();}
            void drawSurface() {
                if(!drawPending.compareAndSet(false,true))return;
                surfaceHandler.post(()->{try{drawSurfaceNow();}finally{drawPending.set(false);}});
            }
            void drawSurfaceNow() {
                if(!getHolder().getSurface().isValid())return;
                Canvas c=null;
                try {
                    c=getHolder().lockHardwareCanvas();if(c==null)return;
                    c.drawColor(foreground && scene!=null?Color.TRANSPARENT:base(requestedStyle),PorterDuff.Mode.SRC);
                    if(!foreground)c.translate(cloudX,cloudY);
                    // Sky uses a shallow surface, but the same virtual full-scene crop as terrain.
                    bounds.set(0,0,c.getWidth(),getResources().getDisplayMetrics().heightPixels+px(60));
                    if(previous!=null && blend<1) {
                        paint.setAlpha(Math.round(255*(1-blend)));c.drawBitmap(previous,null,bounds,paint);
                        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));paint.setAlpha(Math.round(255*blend));
                    }
                    if(scene!=null) {
                        if(foreground && ambientRunning && previous==null && blend>=1 && requestedStyle<2) {
                            if(water==null || waterWidth!=c.getWidth() || waterHeight!=(int)bounds.height()) {
                                waterWidth=c.getWidth();waterHeight=(int)bounds.height();water=new WaterMesh(waterWidth,waterHeight,unit);
                            }
                            water.draw(c,scene,ambientSeconds,paint);
                        } else c.drawBitmap(scene,null,bounds,paint);
                    }
                    paint.setXfermode(null);paint.setAlpha(255);
                } catch(IllegalStateException|IllegalArgumentException ignored){/* Surface may disappear while the TV sleeps. */}
                finally{if(c!=null)try{getHolder().unlockCanvasAndPost(c);}catch(RuntimeException ignored){}}
            }
        }
    }
    static final String[] WALLPAPERS={"Fjord","Dusk","Graphite","Milky Way","Calm Sea","Dawn","Silk","Silver"};
    /** Silver is one choice with two pictures: SILVER by day, SILVER_NIGHT after sunset (see variant()). */
    static final int SILVER=7,SILVER_NIGHT=8;
    static final int[] PHOTOS={R.drawable.wp_milkyway,R.drawable.wp_sea,R.drawable.wp_dawn,R.drawable.wp_silk,R.drawable.wp_silver_day,R.drawable.wp_silver_night};
    static final int[] THUMBS={R.drawable.thumb_0,R.drawable.thumb_1,R.drawable.thumb_2,R.drawable.thumb_3,R.drawable.thumb_4,R.drawable.thumb_5,R.drawable.thumb_6,R.drawable.thumb_7};
    static final class App {String pkg,label,description,tag;Bitmap icon,banner;int color;}
    private int colorFor(String pkg) {
        if(pkg.contains("youtube.tv"))return 0xFFF6F5F5;
        if(pkg.contains("netflix"))return 0xFF141414;
        if(pkg.contains("soundcloud"))return 0xFFFF6900;
        if(pkg.contains("ted.android"))return 0xFFF5F5F5;
        if(pkg.contains("phlox"))return 0xFF304B6C;
        if(pkg.contains("stream.tv"))return 0xFF265EAF;
        if(pkg.contains("tvmusic"))return 0xFF26131B;
        if(pkg.contains("amazon"))return 0xFF142C3E;
        return 0xFF293341;
    }
    private String tagFor(String pkg) {
        if(pkg.contains("youtube.tv"))return "WATCH YOUR WAY";
        if(pkg.contains("netflix"))return "TIME FOR A GREAT STORY";
        if(pkg.contains("soundcloud")||pkg.contains("tvmusic"))return "THE SOUND OF YOUR EVENING";
        if(pkg.contains("ted.android"))return "IDEAS WORTH EXPLORING";
        if(pkg.contains("phlox"))return "A WINDOW TO YOUR WORLD";
        return "YOUR SPACE";
    }
    private String descriptionFor(String pkg) {
        if(pkg.contains("youtube.tv"))return "Favorite creators. New discoveries. A bigger screen.";
        if(pkg.contains("netflix"))return "Great stories. Even better evenings.";
        if(pkg.contains("soundcloud"))return "Find your sound. Set the mood.";
        if(pkg.contains("tvmusic"))return "The artists you love. The mood you are in.";
        if(pkg.contains("ted.android"))return "A new perspective can change everything.";
        if(pkg.contains("phlox"))return "The web, from the comfort of your sofa.";
        if(pkg.contains("disney"))return "Stories for every age, all in one place.";
        if(pkg.contains("spotify"))return "Your music and podcasts, on the big speakers.";
        if(pkg.contains("amazon"))return "Movies and series for your next great evening.";
        return "Your apps. Always within reach.";
    }
}
