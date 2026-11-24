package dev.luma.tv;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;

/** Sunrise and sunset at one place, worked out on the TV with no network. Accurate to a few minutes,
 *  which is all a wallpaper needs. Daylight picks the place from the TV's time zone. */
final class Sun {
    /** Sentinels for a solar event that the estimate cannot place. */
    private static final double STAYS_UP=Double.POSITIVE_INFINITY,STAYS_DOWN=Double.NEGATIVE_INFINITY;
    private static final long HOUR=3600000L,DAY=24*HOUR;
    private final double lat,lon;
    private final ZoneId zone;
    Sun(double lat,double lon,TimeZone zone){this.lat=lat;this.lon=lon;this.zone=zone.toZoneId();}
    boolean isDay(long now){return windowAt(dateAt(now),now).contains(now);}
    /** The next sunrise or sunset after now. In a polar day or night: the next local midnight. */
    long nextChange(long now){
        LocalDate date=dateAt(now);
        Window window=windowAt(date,now);
        if(now<window.rise)return window.rise;
        if(now<window.set)return window.set;
        LocalDate tomorrow=date.plusDays(1);
        if(window.rise==window.set)return startOfDay(tomorrow);
        long nextRise=window(tomorrow).rise;
        return nextRise>now?nextRise:now+HOUR;
    }
    /** The {sunrise, sunset} window used at now, in epoch ms. */
    long[] times(long now){
        Window window=windowAt(dateAt(now),now);
        return new long[]{window.rise,window.set};
    }
    private LocalDate dateAt(long now){return Instant.ofEpochMilli(now).atZone(zone).toLocalDate();}
    private long startOfDay(LocalDate date){return date.atStartOfDay(zone).toInstant().toEpochMilli();}

    private Window windowAt(LocalDate date,long now){
        Window today=window(date);
        if(today.contains(now))return today;
        // A mixed day can start the previous evening or end the following morning.
        Window yesterday=window(date.minusDays(1));
        if(yesterday.spansMidnight&&yesterday.contains(now))return yesterday;
        LocalDate next=date.plusDays(1);
        Window tomorrow=window(next);
        if(tomorrow.spansMidnight&&tomorrow.contains(now))return new Window(tomorrow.rise,startOfDay(next),false);
        return today;
    }
    private Window window(LocalDate date){
        int day=date.getDayOfYear();
        double rise=event(day,6,true),set=event(day,18,false);
        if(Double.isInfinite(rise)&&Double.isInfinite(set)){
            long start=startOfDay(date);
            long end=rise==STAYS_UP||set==STAYS_UP?startOfDay(date.plusDays(1)):start;
            return new Window(start,end,false);
        }

        long noon=date.atTime(12,0).atZone(zone).toInstant().toEpochMilli();
        long midnight=date.toEpochDay()*DAY;
        if(rise==STAYS_UP||set==STAYS_UP){
            // A sunset belongs after solar noon and a sunrise before it, even across a civil midnight.
            long solarNoon=near(midnight+(long)((12-lon/15)*HOUR),noon);
            long start=startOfDay(date),end=startOfDay(date.plusDays(1));
            if(rise==STAYS_UP){
                long sunset=near(midnight+(long)(set*HOUR),solarNoon);
                if(sunset<solarNoon)sunset+=DAY;
                return new Window(start,sunset,sunset>end);
            }
            long sunrise=near(midnight+(long)(rise*HOUR),solarNoon);
            if(sunrise>solarNoon)sunrise-=DAY;
            return new Window(sunrise,end,sunrise<start);
        }

        // One event's estimate missed a short daylight window. Use the same solar declination for both sides.
        if(rise==STAYS_DOWN)rise=event(day,18,true);
        if(set==STAYS_DOWN)set=event(day,6,false);
        return new Window(near(midnight+(long)(rise*HOUR),noon),near(midnight+(long)(set*HOUR),noon),false);
    }
    private static final class Window {
        final long rise,set;
        final boolean spansMidnight;
        Window(long rise,long set,boolean spansMidnight){this.rise=rise;this.set=set;this.spansMidnight=spansMidnight;}
        boolean contains(long now){return now>=rise&&now<set;}
    }
    private static long near(long time, long noon) {
        long offset = (time - noon) % DAY;
        if (offset > DAY/2) offset -= DAY;
        if (offset < -DAY/2) offset += DAY;
        return noon + offset;
    }
    /** UTC hour of sunrise or sunset (the Almanac for Computers algorithm), or STAYS_UP or STAYS_DOWN. */
    private double event(int day,int sampleHour,boolean rising){
        double lngHour=lon/15,t=day+(sampleHour-lngHour)/24;
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
