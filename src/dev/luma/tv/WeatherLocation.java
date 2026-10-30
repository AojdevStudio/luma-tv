package dev.luma.tv;

final class WeatherLocation {
    final String city;
    final double lat,lon;

    WeatherLocation(String city,double lat,double lon){this.city=city;this.lat=lat;this.lon=lon;}

    static WeatherLocation parse(String city,String latitude,String longitude){
        if(city==null||city.trim().isEmpty()||latitude==null||longitude==null)return null;
        double lat,lon;
        try{lat=Double.parseDouble(latitude);lon=Double.parseDouble(longitude);}
        catch(NumberFormatException error){return null;}
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat < -90||lat > 90||lon < -180||lon > 180)return null;
        return new WeatherLocation(city.trim(),lat,lon);
    }
}
