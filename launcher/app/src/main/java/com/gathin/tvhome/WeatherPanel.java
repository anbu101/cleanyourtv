package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Weather forecast from Open-Meteo: no account, no key, no location permission. The launcher's
 * INTERNET permission is all that's needed.
 *
 * <p>What leaves the TV: the city name the user typed (to geocode it), then that place's latitude
 * and longitude (for the forecast and air quality), and — only when the user taps "Locate" —
 * one request to ipinfo.io for the coarse position, which is then resolved through the geocoder.
 * Nothing runs in the background: requests happen while the home screen is visible.
 */
final class WeatherPanel {

    static final String PREFS = "weather_panel";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_NAME = "name";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LON = "lon";

    private static final String GEO = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast";
    private static final String AIR = "https://air-quality-api.open-meteo.com/v1/air-quality";
    private static final String IP_GEO = "https://ipinfo.io/json";

    static final long REFRESH_MS = 30 * 60 * 1000L;

    private final SharedPreferences prefs;
    private final String lang;

    WeatherPanel(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String l = Locale.getDefault().getLanguage();
        lang = (l == null || l.isEmpty()) ? "en" : l;
    }

    boolean enabled() { return prefs.getBoolean(KEY_ENABLED, false); }
    void enabled(boolean on) { prefs.edit().putBoolean(KEY_ENABLED, on).apply(); }
    boolean hasPlace() { return prefs.contains(KEY_NAME); }
    String placeName() { return prefs.getString(KEY_NAME, ""); }
    double lat() { return Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0)); }
    double lon() { return Double.longBitsToDouble(prefs.getLong(KEY_LON, 0)); }

    void place(String name, double lat, double lon) {
        prefs.edit().putString(KEY_NAME, name)
                .putLong(KEY_LAT, Double.doubleToRawLongBits(lat))
                .putLong(KEY_LON, Double.doubleToRawLongBits(lon))
                .apply();
    }

    /** Common cities for quick selection — no typing needed. */
    static final String[][] COMMON = {
        {"Mumbai", "India"}, {"Delhi", "India"}, {"Bangalore", "India"}, {"Chennai", "India"},
        {"Hyderabad", "India"}, {"Kolkata", "India"}, {"Pune", "India"}, {"Ahmedabad", "India"},
        {"Jaipur", "India"}, {"Lucknow", "India"}, {"Coimbatore", "India"}, {"Kochi", "India"},
        {"Chandigarh", "India"}, {"Indore", "India"}, {"Bhopal", "India"}, {"Visakhapatnam", "India"},
        {"Nagpur", "India"}, {"Patna", "India"}, {"Guwahati", "India"}, {"Thiruvananthapuram", "India"},
        {"London", "UK"}, {"New York", "USA"}, {"Dubai", "UAE"}, {"Singapore", "Singapore"},
        {"Tokyo", "Japan"}, {"Sydney", "Australia"}, {"Toronto", "Canada"}, {"Paris", "France"},
    };

    // ---- places -----------------------------------------------------------------------------------

    static final class Place {
        final String name, detail;
        final double lat, lon;
        Place(String name, String detail, double lat, double lon) {
            this.name = name;
            this.detail = detail == null ? "" : detail;
            this.lat = lat;
            this.lon = lon;
        }
        String row() { return detail.isEmpty() ? name : name + " · " + detail; }
    }

    List<Place> search(String typed) throws Exception { return searchIn(typed, lang); }

    List<Place> searchIn(String typed, String language) throws Exception {
        String json = get(GEO + "?name=" + enc(typed) + "&count=8&language=" + enc(language) + "&format=json");
        JSONArray results = new JSONObject(json).optJSONArray("results");
        List<Place> out = new ArrayList<>();
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String detail = r.optString("admin1", "");
            String country = r.optString("country", "");
            if (!country.isEmpty()) detail = detail.isEmpty() ? country : detail + ", " + country;
            out.add(new Place(r.optString("name"), detail, r.optDouble("latitude"), r.optDouble("longitude")));
        }
        return out;
    }

    Place locate() throws Exception {
        JSONObject ip = new JSONObject(get(IP_GEO));
        String city = ip.optString("city", "");
        String country = ip.optString("country", "");
        String loc = ip.optString("loc", "");
        if (city.isEmpty() && loc.isEmpty()) return null;
        if (!city.isEmpty()) {
            List<Place> found = search(city);
            if (!found.isEmpty()) return found.get(0);
        }
        if (!loc.isEmpty()) {
            String[] ll = loc.split(",");
            if (ll.length == 2) {
                return new Place(city.isEmpty() ? loc : city, country,
                        Double.parseDouble(ll[0].trim()), Double.parseDouble(ll[1].trim()));
            }
        }
        return null;
    }

    List<Place> common() {
        List<Place> out = new ArrayList<>();
        for (String[] c : COMMON) {
            double[] xy = cityCentre(c[0]);
            if (xy != null) out.add(new Place(c[0], c[1], xy[0], xy[1]));
        }
        return out;
    }

    private static double[] cityCentre(String name) {
        switch (name) {
            case "Mumbai":              return new double[]{19.0760, 72.8777};
            case "Delhi":               return new double[]{28.6139, 77.2090};
            case "Bangalore":           return new double[]{12.9716, 77.5946};
            case "Chennai":             return new double[]{13.0827, 80.2707};
            case "Hyderabad":           return new double[]{17.3850, 78.4867};
            case "Kolkata":             return new double[]{22.5726, 88.3639};
            case "Pune":                return new double[]{18.5204, 73.8567};
            case "Ahmedabad":           return new double[]{23.0225, 72.5714};
            case "Jaipur":              return new double[]{26.9124, 75.7873};
            case "Lucknow":             return new double[]{26.8467, 80.9462};
            case "Coimbatore":          return new double[]{11.0168, 76.9558};
            case "Kochi":               return new double[]{9.9312, 76.2673};
            case "Chandigarh":          return new double[]{30.7333, 76.7794};
            case "Indore":              return new double[]{22.7196, 75.8577};
            case "Bhopal":              return new double[]{23.2599, 77.4126};
            case "Visakhapatnam":       return new double[]{17.6868, 83.2185};
            case "Nagpur":              return new double[]{21.1458, 79.0882};
            case "Patna":               return new double[]{25.6093, 85.1376};
            case "Guwahati":            return new double[]{26.1445, 91.7362};
            case "Thiruvananthapuram":   return new double[]{8.5241, 76.9366};
            case "London":              return new double[]{51.5072, -0.1276};
            case "New York":            return new double[]{40.7128, -74.0060};
            case "Dubai":               return new double[]{25.2048, 55.2708};
            case "Singapore":           return new double[]{1.3521, 103.8198};
            case "Tokyo":               return new double[]{35.6895, 139.6917};
            case "Sydney":              return new double[]{-33.8688, 151.2093};
            case "Toronto":             return new double[]{43.6532, -79.3832};
            case "Paris":               return new double[]{48.8566, 2.3522};
            default: return null;
        }
    }

    // ---- the report -------------------------------------------------------------------------------

    static final class Day {
        final String label;
        final int code, max, min;
        Day(String label, int code, int max, int min) {
            this.label = label; this.code = code; this.max = max; this.min = min;
        }
    }

    static final class Report {
        int temp, feels, code, aqi = -1;
        final List<Day> days = new ArrayList<>();
        final List<Hour> hours = new ArrayList<>();
    }

    static final class Hour {
        final String label;
        final int code, temp;
        Hour(String label, int code, int temp) {
            this.label = label; this.code = code; this.temp = temp;
        }
    }

    Report fetch() throws Exception {
        String url = FORECAST + "?latitude=" + lat() + "&longitude=" + lon()
                + "&current=temperature_2m,apparent_temperature,weather_code"
                + "&hourly=temperature_2m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=4";
        JSONObject json = new JSONObject(get(url));
        Report r = new Report();
        r.temp = Math.round((float) json.getJSONObject("current").optDouble("temperature_2m"));
        r.feels = Math.round((float) json.getJSONObject("current").optDouble("apparent_temperature"));
        r.code = json.getJSONObject("current").optInt("weather_code");
        JSONObject daily = json.getJSONObject("daily");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");
        JSONArray times = daily.getJSONArray("time");
        int n = Math.min(codes.length(), 4);
        for (int i = 0; i < n; i++) {
            r.days.add(new Day(times.optString(i), codes.optInt(i),
                    Math.round((float) max.optDouble(i)), Math.round((float) min.optDouble(i))));
        }
        try {
            JSONObject hourly = json.getJSONObject("hourly");
            JSONArray hTimes = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray hCodes = hourly.getJSONArray("weather_code");
            String now = json.getJSONObject("current").optString("time");
            int from = 0;
            for (int i = 0; i < hTimes.length(); i++) {
                if (hTimes.optString(i).compareTo(now) >= 0) { from = i; break; }
            }
            int to = Math.min(hTimes.length(), from + 8);
            for (int i = from; i < to; i++) {
                String t = hTimes.optString(i);
                int hour = 0;
                int sep = t.indexOf('T');
                if (sep > 0 && t.length() >= sep + 6) {
                    hour = Integer.parseInt(t.substring(sep + 1, sep + 3));
                }
                r.hours.add(new Hour(hour + ":00", hCodes.optInt(i),
                        Math.round((float) temps.optDouble(i))));
            }
        } catch (Exception ignored) { }
        try {
            JSONObject air = new JSONObject(get(AIR + "?latitude=" + lat() + "&longitude=" + lon() + "&current=us_aqi"));
            r.aqi = air.getJSONObject("current").optInt("us_aqi", -1);
        } catch (Exception ignored) { }
        return r;
    }

    // ---- WMO weather codes ------------------------------------------------------------------------

    static int skyOf(int code) {
        if (code == 0 || code == 1) return 0;            // clear
        if (code == 2 || code == 3) return 1;            // cloudy
        if (code == 45 || code == 48) return 2;          // fog
        if (code >= 51 && code <= 57) return 3;          // drizzle
        if (code >= 61 && code <= 67) return 4;          // rain
        if (code >= 71 && code <= 77) return 5;          // snow
        if (code >= 80 && code <= 82) return 6;          // showers
        if (code == 85 || code == 86) return 7;          // snow showers
        return 8;                                        // thunder
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private static String enc(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "CleanYourTV");
            InputStream in = c.getResponseCode() == HttpURLConnection.HTTP_OK
                    ? c.getInputStream() : c.getErrorStream();
            if (in == null) throw new java.io.IOException("HTTP " + c.getResponseCode());
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
