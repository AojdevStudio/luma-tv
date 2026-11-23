package dev.luma.tv;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class SunTest {
    static void expect(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("ok    "+message);}
    static long at(String instant){return Instant.parse(instant).toEpochMilli();}
    static long local(String zone,String dateTime){return LocalDateTime.parse(dateTime).atZone(ZoneId.of(zone)).toInstant().toEpochMilli();}
    /** The zone's place from res/raw/zones.txt, the same file the TV reads. */
    static Sun sun(Map<String,double[]> zones,String id){double[] p=zones.get(id);return new Sun(p[0],p[1],TimeZone.getTimeZone(id));}

    public static void main(String[] args) throws IOException{
        Map<String,double[]> zones=new LinkedHashMap<>();
        for(String line:Files.readAllLines(Paths.get("res/raw/zones.txt"),StandardCharsets.UTF_8)){if(line.startsWith("#"))continue;String[] f=line.split("\\|");zones.put(f[0],new double[]{Double.parseDouble(f[1]),Double.parseDouble(f[2])});}

        Sun svalbard=sun(zones,"Arctic/Longyearbyen");long solstice=at("2026-06-21T17:00:00Z");
        long[] day=svalbard.times(solstice);
        expect(day[0]==local("Arctic/Longyearbyen","2026-06-21T00:00")&&day[1]==local("Arctic/Longyearbyen","2026-06-22T00:00"),"polar day in Longyearbyen runs from local midnight to local midnight");
        expect(svalbard.isDay(solstice)&&svalbard.isDay(local("Arctic/Longyearbyen","2026-06-21T00:30"))&&svalbard.isDay(local("Arctic/Longyearbyen","2026-06-21T23:30")),"polar day in Longyearbyen is day at every hour");
        expect(svalbard.nextChange(solstice)==local("Arctic/Longyearbyen","2026-06-22T00:00"),"polar day next change is the next local midnight");

        long winter=at("2026-12-21T11:00:00Z");long[] night=svalbard.times(winter);
        expect(night[0]==night[1]&&night[0]==local("Arctic/Longyearbyen","2026-12-21T00:00"),"polar night in Longyearbyen is an empty day at local midnight");
        expect(!svalbard.isDay(winter)&&svalbard.nextChange(winter)==local("Arctic/Longyearbyen","2026-12-22T00:00"),"polar night is night and next change is the next local midnight");

        String resoluteZone="America/Resolute";
        Sun resolute=sun(zones,resoluteZone);
        expect(resolute.isDay(solstice)&&!resolute.isDay(winter),"Resolute has polar day in June and polar night in December");

        long lastNight=local(resoluteZone,"2026-02-03T12:30");
        expect(!resolute.isDay(lastNight)&&resolute.nextChange(lastNight)==local(resoluteZone,"2026-02-04T00:00"),"Resolute stays dark February 3 and checks at the next local midnight");
        long firstDay=local(resoluteZone,"2026-02-04T12:30");
        long[] firstLight=resolute.times(firstDay);
        // Published sunrise 12:09 and sunset 12:58 in Resolute Bay.
        expect(resolute.isDay(firstDay)&&Math.abs(firstLight[0]-local(resoluteZone,"2026-02-04T12:09"))<10*60000
            &&Math.abs(firstLight[1]-local(resoluteZone,"2026-02-04T12:58"))<10*60000,"Resolute has a short daylight window on February 4");
        expect(!resolute.isDay(firstLight[0]-1)&&resolute.nextChange(firstLight[0]-1)==firstLight[0]
            &&resolute.isDay(firstLight[0])&&resolute.nextChange(firstDay)==firstLight[1]&&!resolute.isDay(firstLight[1])
            &&resolute.nextChange(firstLight[1])==resolute.times(local(resoluteZone,"2026-02-05T12:30"))[0],"Resolute switches exactly at both February 4 events and schedules the next sunrise");

        String trollZone="Antarctica/Troll";
        Sun troll=sun(zones,trollZone);
        long trollDay=local(trollZone,"2026-02-01T23:15"),trollNight=local(trollZone,"2026-02-01T23:50");
        long trollSunset=troll.times(trollDay)[1];
        long trollSunrise=troll.times(local(trollZone,"2026-02-02T00:00"))[0];
        expect(troll.isDay(trollDay)&&!troll.isDay(trollNight)&&trollSunset==at("2026-02-01T23:36:24.509Z"),"Troll keeps its finite February 1 sunset after polar day");
        expect(troll.nextChange(trollDay)==trollSunset&&!troll.isDay(trollSunset)
            &&troll.nextChange(trollNight)==trollSunrise&&trollSunrise>local(trollZone,"2026-02-02T00:00"),"Troll schedules the exact sunset and the following sunrise");
        long polarDayRise=troll.times(local(trollZone,"2026-11-10T12:00"))[0];
        expect(Math.abs(polarDayRise-local(trollZone,"2026-11-09T23:55"))<5*60000
            &&!troll.isDay(polarDayRise-1)&&troll.nextChange(polarDayRise-1)==polarDayRise&&troll.isDay(polarDayRise)
            &&troll.nextChange(polarDayRise)==local(trollZone,"2026-11-10T00:00"),"Troll keeps the sunrise before midnight when polar day returns");

        String svalbardZone="Arctic/Longyearbyen";
        long lastPolarDay=local(svalbardZone,"2026-08-24T12:00"),afterMidnight=local(svalbardZone,"2026-08-25T00:10");
        long lateSunset=svalbard.times(lastPolarDay)[1];
        expect(svalbard.isDay(lastPolarDay)&&svalbard.isDay(afterMidnight)&&!svalbard.isDay(lateSunset)
            &&Math.abs(lateSunset-local(svalbardZone,"2026-08-25T00:28"))<10*60000
            &&svalbard.nextChange(lastPolarDay)==lateSunset&&svalbard.nextChange(afterMidnight)==lateSunset,"Longyearbyen keeps the first sunset after midnight on the right date");

        Sun thule=sun(zones,"America/Thule");
        // Midnight starts at UTC−03:00 and the next midnight is at UTC−04:00.
        long thuleStart=at("2026-11-01T03:00:00Z"),thuleNext=at("2026-11-02T04:00:00Z");
        expect(local("America/Thule","2026-11-01T00:00")==thuleStart&&local("America/Thule","2026-11-02T00:00")==thuleNext
            &&!thule.isDay(thuleStart+12*3600000L)&&thule.nextChange(thuleStart)==thuleNext
            &&thule.nextChange(thuleNext-1)==thuleNext,"Thule stays dark and checks at the next local midnight on a 25-hour day");

        Sun oslo=sun(zones,"Europe/Oslo");long[] summer=oslo.times(solstice);
        expect(Math.abs(summer[0]-local("Europe/Oslo","2026-06-21T03:54"))<5*60000&&Math.abs(summer[1]-local("Europe/Oslo","2026-06-21T22:44"))<5*60000,"Oslo solstice sunrise near 03:54 and sunset near 22:44");
        long early=local("Europe/Oslo","2026-06-21T02:00");
        expect(!oslo.isDay(early)&&oslo.nextChange(early)==summer[0]&&oslo.isDay(solstice)&&oslo.nextChange(solstice)==summer[1],"Oslo is night before sunrise and day before sunset, and each next change is that event");

        long start=at("2026-01-01T00:00:00Z");int checked=0;
        for(Map.Entry<String,double[]> zone:zones.entrySet()){
            Sun s=sun(zones,zone.getKey());
            for(long now=start;now<start+366*86400000L;now+=31*3600000L){
                long next=s.nextChange(now);
                if(next<=now||next>now+2*86400000L)throw new AssertionError(zone.getKey()+" at "+Instant.ofEpochMilli(now)+": next change "+Instant.ofEpochMilli(next));
                checked++;
            }
        }
        expect(checked>zones.size()*280,"every zone in zones.txt gives a next change within two days, all year ("+checked+" checks)");
    }
}
