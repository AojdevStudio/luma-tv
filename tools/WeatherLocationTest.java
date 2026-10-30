package dev.luma.tv;

public final class WeatherLocationTest {
    static void expect(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("ok    "+message);}

    public static void main(String[] args){
        WeatherLocation location=WeatherLocation.parse(" Test city ","29.46","-95.36");
        expect(location!=null&&location.city.equals("Test city")&&location.lat==29.46&&location.lon==-95.36,"valid location keeps its name and coordinates");
        expect(WeatherLocation.parse("Test city","-90","-180")!=null&&WeatherLocation.parse("Test city","90","180")!=null,"coordinate bounds are accepted");
        for(String value:new String[]{"NaN","Infinity","-Infinity","999","1e3",""})expect(WeatherLocation.parse("Test city",value,"0")==null,"invalid latitude rejected: "+value);
        expect(WeatherLocation.parse("Test city","0","NaN")==null&&WeatherLocation.parse("Test city","0","Infinity")==null&&WeatherLocation.parse("Test city","0","-Infinity")==null,"nonfinite longitude is rejected");
        expect(WeatherLocation.parse("Test city","0","181")==null&&WeatherLocation.parse("Test city","0","-181")==null,"longitude outside bounds is rejected");
        expect(WeatherLocation.parse(" ","0","0")==null&&WeatherLocation.parse(null,"0","0")==null&&WeatherLocation.parse("\u00a0","0","0")==null&&WeatherLocation.parse("\u200b","0","0")==null&&WeatherLocation.parse("\u3000","0","0")==null,"missing or invisible city is rejected");
    }
}
