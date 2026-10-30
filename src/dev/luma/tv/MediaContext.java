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

    /** One forward pass knows at each character whether a host ends there, so a word glued before the host cannot
     *  hide it. A host, with one root dot or a port, is a link before a path, query or fragment. After //, it is a
     *  link as soon as it is complete, so punctuation after it cannot hide it. */
    private static boolean link(String token){
        String lower=token.toLowerCase(Locale.ROOT);
        if(lower.contains("://")||lower.contains("www.")||lower.contains("mailto:"))return true;
        int label=0,port=-1;
        boolean dotted=false,letters=true,host=false,bracket=false,colon=false,relative=false;
        for(int i=0;i<token.length();i++){
            char c=token.charAt(i);
            if(Character.isLetterOrDigit(c)||c=='-'||c=='_'){
                letters&=Character.isLetter(c);
                port=port>=0&&Character.isDigit(c)?port+1:-1;
                host=dotted&&(letters&&i>label||i-label>3&&lowerAt(token,label,"xn--"))||i>=8&&lowerAt(token,i-8,"localhost")||ipv4(token,i+1);
            }else if(c=='.'){
                dotted=i>label;
                host=host&&dotted;
                letters=true;label=i+1;port=-1;
            }else{
                if(c=='/'||c=='?'||c=='#'){
                    if(host||port>0)return true;
                    relative=c=='/'&&i>0&&token.charAt(i-1)=='/';
                    bracket=false;
                }
                if(c=='['){bracket=true;colon=false;}
                if(c==':')colon=true;
                port=c==':'&&host?0:-1;
                host=c==']'&&bracket&&colon;
                dotted=false;letters=true;label=i+1;
            }
            if(relative&&host)return true;
        }
        return false;
    }

    /** Four dot-separated groups of one to three digits end at end. Characters glued before the first group do not matter. */
    private static boolean ipv4(String token,int end){
        for(int group=0;group<4;group++){
            int start=end;
            while(start>0&&end-start<3&&Character.isDigit(token.charAt(start-1)))start--;
            if(start==end||group<3&&(start==0||token.charAt(start-1)!='.'))return false;
            end=start-1;
        }
        return true;
    }

    /** Lower-cases as Locale.ROOT does for these ASCII words. regionMatches(true,...) would also read ſ as s. */
    private static boolean lowerAt(String token,int at,String word){
        for(int i=0;i<word.length();i++)if(Character.toLowerCase(token.charAt(at+i))!=word.charAt(i))return false;
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
