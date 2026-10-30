package dev.luma.tv;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;

/** What Home says about one media item: the app it comes from, whether the data is live or a demo fixture, how far
 *  in it is, and a summary only when the app shared a description. Plain Java, so tools/MediaContextTest.java runs on
 *  a computer. It never holds a link, an intent or notification text: callers pass the app's label and the plain
 *  metadata the app published (Watch Next, media session), and links are cut out of descriptions. */
final class MediaContext {
    enum Data {
        LIVE("Live"),DEMO("Demo");
        final String label;
        Data(String label){this.label=label;}
    }
    static final long MIN=60000;
    static final int SUMMARY_MAX=140;

    final String source;
    final Data data;
    /** Fraction watched, 0 to 1, or -1 when the app shared no progress. */
    final float progress;
    /** Milliseconds left and in total, or -1 when unknown. */
    final long left,duration;
    /** A concise plain-text description, or null when the app shared none. */
    final String summary;

    private MediaContext(String source,Data data,float progress,long left,long duration,String summary){
        this.source=source==null||source.isEmpty()?"Unknown app":source;this.data=data;this.progress=progress;this.left=left;this.duration=duration;this.summary=summary;
    }
    /** From an app's own position and duration (Watch Next, a media session); zero or less means it shared none. */
    static MediaContext watched(String source,Data data,long position,long duration,String description){
        boolean known=duration>0&&position>0;
        return new MediaContext(source,data,known?Math.min(1,position/(float)duration):-1,known?Math.max(0,duration-position):-1,known?duration:-1,concise(description));
    }

    String progressText(){return left<0?"Progress unavailable":span(left)+" left";}
    String summaryText(){return summary!=null?summary:"Summary unavailable. "+source+" shared no description.";}
    /** Source and data state, as in "Plex · Demo". */
    String label(){return source+" · "+data.label;}
    static String span(long ms){long m=Math.max(1,ms/MIN);return m<60?m+" m":String.format(Locale.US,"%d h %02d m",m/60,m%60);}

    /** The Up Next caption tag: "Plex, demo data" when every item comes from one app, else "Live data" or "Demo data". */
    static String tag(Data data,Collection<String> sources){
        if(sources.isEmpty())return "";
        return new HashSet<>(sources).size()==1?sources.iterator().next()+", "+data.label.toLowerCase(Locale.ENGLISH)+" data":data.label+" data";
    }

    static String concise(String raw){
        if(raw==null)return null;
        String text=raw.replaceAll("(?i)\\S*(?:://|www\\.|mailto:|[a-z0-9.-]+\\.[a-z]{2,}(?::[0-9]+)?[/?#])\\S*"," ").replaceAll("[\\s\\p{Cntrl}]+"," ").trim();
        if(text.isEmpty())return null;
        if(text.length()<=SUMMARY_MAX)return text;
        String head=text.substring(0,SUMMARY_MAX);
        int space=head.lastIndexOf(' ',SUMMARY_MAX-1);
        return (space>0?head.substring(0,space):head.substring(0,SUMMARY_MAX-1))+"…";
    }

    /** Demo fixtures for the promo mode (Promo.on), copied from the approved design B mockup's demo data. */
    static final class Demo {
        final String title;final MediaContext context;
        Demo(String title,MediaContext context){this.title=title;this.context=context;}
    }
    static final Demo[] DEMO={
        new Demo("Demo movie",new MediaContext("Plex",Data.DEMO,.42f,72*MIN,125*MIN,"Demo summary text. Hidden when Plex shares none.")),
        new Demo("Demo series, S2 E4",new MediaContext("Plex",Data.DEMO,.70f,18*MIN,-1,null)),
        new Demo("Demo show, S1 E1",new MediaContext("Plex",Data.DEMO,.10f,40*MIN,-1,null)),
    };
}
