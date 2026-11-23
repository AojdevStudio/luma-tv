package dev.luma.tv;

import java.util.*;

/** Sunrise and sunset at one place, worked out on the TV with no network. Accurate to a few minutes,
 *  which is all a wallpaper needs. Daylight picks the place from the TV's time zone. */
final class Sun {
    /** event() results for a day with no sunset or no sunrise. They are infinite, so they never reach near(). */
    private static final double STAYS_UP=Double.POSITIVE_INFINITY,STAYS_DOWN=Double.NEGATIVE_INFINITY;
    private final double lat,lon;
    private final TimeZone zone;
    Sun(double lat,double lon,TimeZone zone){this.lat=lat;this.lon=lon;this.zone=zone;}
    boolean isDay(long now){long[] t=times(now);return now>=t[0]&&now<t[1];}
    /** The next sunrise or sunset after now. In a polar day or night: the next local midnight. */
    long nextChange(long now){
        long[] t=times(now);if(now<t[0])return t[0];if(now<t[1])return t[1];
        long[] tomorrow=times(now+86400000L);return tomorrow[0]>now?tomorrow[0]:now+3600000L;
    }
    /** Today's {sunrise, sunset} in epoch ms. Polar day: local midnight to the next local midnight.
     *  Polar night: both at local midnight, so the day is empty. */
    long[] times(long now){
        Calendar c=Calendar.getInstance(zone);c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        long start=c.getTimeInMillis();
        c.set(Calendar.HOUR_OF_DAY,12);long noon=c.getTimeInMillis();int n=c.get(Calendar.DAY_OF_YEAR);
        double rise=event(n,true),set=event(n,false);
        if(rise==STAYS_UP||set==STAYS_UP){c.add(Calendar.DAY_OF_MONTH,1);c.set(Calendar.HOUR_OF_DAY,0);return new long[]{start,c.getTimeInMillis()};}
        if(rise==STAYS_DOWN||set==STAYS_DOWN)return new long[]{start,start};
        Calendar utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));utc.clear();utc.set(c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH));
        long midnight=utc.getTimeInMillis();
        return new long[]{near(midnight+(long)(rise*3600000),noon),near(midnight+(long)(set*3600000),noon)};
    }
    private static long near(long t,long noon){while(t-noon>43200000L)t-=86400000L;while(noon-t>43200000L)t+=86400000L;return t;}
    /** UTC hour of sunrise or sunset (the Almanac for Computers algorithm), or STAYS_UP or STAYS_DOWN. */
    private double event(int day,boolean rising){
        double lngHour=lon/15,t=day+((rising?6:18)-lngHour)/24;
        double m=0.9856*t-3.289;
        double l=norm(m+1.916*sin(m)+0.020*sin(2*m)+282.634,360);
        double ra=norm(Math.toDegrees(Math.atan(0.91764*Math.tan(Math.toRadians(l)))),360);
        ra=(ra+Math.floor(l/90)*90-Math.floor(ra/90)*90)/15;
        double sinDec=0.39782*sin(l),cosDec=Math.cos(Math.asin(sinDec));
        double cosH=(Math.cos(Math.toRadians(90.833))-sinDec*sin(lat))/(cosDec*Math.cos(Math.toRadians(lat)));
        if(cosH>1)return STAYS_DOWN;if(cosH<-1)return STAYS_UP;
        double h=(rising?360-Math.toDegrees(Math.acos(cosH)):Math.toDegrees(Math.acos(cosH)))/15;
        return norm(h+ra-0.06571*t-6.622-lngHour,24);
    }
    private static double sin(double degrees){return Math.sin(Math.toRadians(degrees));}
    private static double norm(double v,double range){v%=range;return v<0?v+range:v;}
}
