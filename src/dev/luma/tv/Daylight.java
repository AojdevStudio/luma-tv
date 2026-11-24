package dev.luma.tv;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The TV's place, from the main city of its time zone (res/raw/zones.txt): no location permission, no network. */
final class Daylight {
    private Daylight(){}
    /** Sunrise and sunset at the time zone's main city. An unknown zone uses latitude 45 on its offset's meridian. */
    static Sun sun(Context context){
        TimeZone zone=TimeZone.getDefault();
        double[] place=lookup(context,zone.getID());
        if(place==null)place=new double[]{45,zone.getRawOffset()/3600000.0*15};
        return new Sun(place[0],place[1],zone);
    }
    /** The time zone's main city: {latitude, longitude}, or null for an unknown zone. */
    static double[] place(Context context){return lookup(context,TimeZone.getDefault().getID());}
    /** "Europe/Paris" → "Paris", "America/New_York" → "New York". */
    static String city(){String id=TimeZone.getDefault().getID();return id.substring(id.lastIndexOf('/')+1).replace('_',' ');}
    private static double[] lookup(Context context,String id){
        int res=context.getResources().getIdentifier("zones","raw",context.getPackageName());if(res==0)return null;
        try(BufferedReader r=new BufferedReader(new InputStreamReader(context.getResources().openRawResource(res),StandardCharsets.UTF_8))){
            String line;while((line=r.readLine())!=null){if(!line.startsWith(id+"|"))continue;String[] f=line.split("\\|");return new double[]{Double.parseDouble(f[1]),Double.parseDouble(f[2])};}
        }catch(IOException|RuntimeException ignored){}
        return null;
    }
}
