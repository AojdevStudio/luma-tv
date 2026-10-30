package dev.luma.tv;

final class WeatherLocation {
    final String city;
    final double lat,lon;

    WeatherLocation(String city,double lat,double lon){this.city=city;this.lat=lat;this.lon=lon;}

    static WeatherLocation parse(String city,String latitude,String longitude){
        if(city==null||latitude==null||longitude==null)return null;
        String name=city.trim();
        if(name.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.getType(c)==Character.FORMAT))return null;
        double lat,lon;
        try{lat=Double.parseDouble(latitude);lon=Double.parseDouble(longitude);}
        catch(NumberFormatException error){return null;}
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat < -90||lat > 90||lon < -180||lon > 180)return null;
        return new WeatherLocation(name,lat,lon);
    }
}
