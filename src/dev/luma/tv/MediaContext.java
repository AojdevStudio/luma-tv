package dev.luma.tv;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;

/** What Home says about one media item: the app it comes from, whether the data is live or a demo fixture, how far
 *  in it is, and a summary only when the app shared a description. Callers pass display metadata from Watch Next
 *  or a media session, never provider intents or notification content. */
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
    String label(){return source+" · "+data.label;}
    static String span(long ms){long m=Math.max(1,ms/MIN);return m<60?m+" m":String.format(Locale.US,"%d h %02d m",m/60,m%60);}

    static String tag(Data data,Collection<String> sources){
        if(sources.isEmpty())return "";
        return new HashSet<>(sources).size()==1?sources.iterator().next()+", "+data.label.toLowerCase(Locale.ENGLISH)+" data":data.label+" data";
    }

    static String concise(String raw){
        if(raw==null)return null;
        StringBuilder clean=new StringBuilder();
        int start=0;
        for(int end=0;end<=raw.length();end++){
            if(end<raw.length()&&!Character.isWhitespace(raw.charAt(end))&&!Character.isISOControl(raw.charAt(end)))continue;
            if(start<end){
                String token=raw.substring(start,end);
                if(!link(token)){if(clean.length()>0)clean.append(' ');clean.append(token);}
            }
            start=end+1;
        }
        if(clean.length()==0)return null;
        String text=clean.toString();
        if(text.length()<=SUMMARY_MAX)return text;
        String head=text.substring(0,SUMMARY_MAX);
        int space=head.lastIndexOf(' ',SUMMARY_MAX-1);
        return (space>0?head.substring(0,space):head.substring(0,SUMMARY_MAX-1))+"…";
    }

    private static boolean link(String token){
        String lower=token.toLowerCase(Locale.ROOT);
        if(lower.contains("://")||lower.contains("www.")||lower.contains("mailto:"))return true;
        int start=0;
        for(int end=0;end<=token.length();end++){
            boolean finished=end==token.length();
            if(!finished&&"/?#".indexOf(token.charAt(end))<0)continue;
            boolean relative=start>=2&&token.charAt(start-1)=='/'&&token.charAt(start-2)=='/';
            if(start<end&&(!finished||relative)&&authority(token.substring(start,end)))return true;
            start=end+1;
        }
        return false;
    }

    private static boolean authority(String token){
        int end=token.length();
        int digits=end;
        while(digits>0&&Character.isDigit(token.charAt(digits-1)))digits--;
        if(digits<end&&digits>0&&token.charAt(digits-1)==':')end=digits-1;
        if(end>0&&token.charAt(end-1)==']'){
            int bracket=token.lastIndexOf('[',end-1);
            if(bracket>=0&&token.substring(bracket,end).indexOf(':')>=0)return true;
        }
        if(end>0&&token.charAt(end-1)=='.')end--;
        int start=end;
        while(start>0){
            char c=token.charAt(start-1);
            if(!Character.isLetterOrDigit(c)&&c!='.'&&c!='-'&&c!='_')break;
            start--;
        }
        if(start==end)return false;
        String host=token.substring(start,end).toLowerCase(Locale.ROOT);
        if(host.equals("localhost"))return true;
        String[] labels=host.split("\\.",-1);
        for(String label:labels)if(label.isEmpty())return false;
        if(labels.length<2)return false;
        String suffix=labels[labels.length-1];
        if(suffix.startsWith("xn--")&&suffix.length()>4)return true;
        boolean letters=suffix.length()>=2;
        for(int i=0;i<suffix.length();i++)letters&=Character.isLetter(suffix.charAt(i));
        if(letters)return true;
        if(labels.length!=4)return false;
        for(String label:labels){
            if(label.length()>3)return false;
            for(int i=0;i<label.length();i++)if(!Character.isDigit(label.charAt(i)))return false;
        }
        return true;
    }

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
