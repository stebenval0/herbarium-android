package com.herbarium.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/** Weather from Open-Meteo (no account needed), kept on the phone so the home screen never waits for it. */
final class Weather {
    private Weather() {}

    static String place(Context c) { return Theme.prefs(c).getString("place", "Baton Rouge"); }
    static double lat(Context c) { return Double.longBitsToDouble(Theme.prefs(c).getLong("lat", Double.doubleToLongBits(30.4515))); }
    static double lon(Context c) { return Double.longBitsToDouble(Theme.prefs(c).getLong("lon", Double.doubleToLongBits(-91.1871))); }

    static JSONObject cached(Context c) {
        try { return new JSONObject(Theme.prefs(c).getString("weather", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static boolean stale(Context c) { return System.currentTimeMillis() - Theme.prefs(c).getLong("weatherAt", 0) > 15 * 60_000L; }

    /** Fetch (on a background thread). Returns true if something new arrived. */
    static boolean refresh(Context c) {
        try {
            String tz = TimeZone.getDefault().getID();
            URL u = new URL(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day"
                    + "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=16"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset&forecast_days=7"
                    + "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=%s", lat(c), lon(c), java.net.URLEncoder.encode(tz, "UTF-8")));
            HttpURLConnection h = (HttpURLConnection) u.openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(10000); h.setRequestProperty("User-Agent", "Herbarium/2");
            if (h.getResponseCode() != 200) return false;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
            JSONObject j = new JSONObject(b.toString("UTF-8"));
            SharedPreferences.Editor e = Theme.prefs(c).edit().putString("weather", j.toString()).putLong("weatherAt", System.currentTimeMillis());
            JSONObject d = j.optJSONObject("daily");
            if (d != null) {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
                try {
                    e.putLong("sunrise", f.parse(d.getJSONArray("sunrise").getString(0)).getTime());
                    e.putLong("sunset", f.parse(d.getJSONArray("sunset").getString(0)).getTime());
                } catch (Exception ignored) {}
            }
            e.apply();
            return true;
        } catch (Exception e) { return false; }
    }

    static String words(int code) {
        return code == 0 ? "clear skies" : code == 1 ? "mostly fair" : code == 2 ? "scattered clouds" : code == 3 ? "overcast"
                : code <= 48 ? "misty" : code <= 57 ? "drizzle" : code <= 67 ? "rain" : code <= 77 ? "snow" : code <= 82 ? "showers" : "thunderstorms";
    }

    /** "Baton Rouge · 67° · clear skies", or "" before the first fetch. */
    static String line(Context c) {
        JSONObject cur = cached(c).optJSONObject("current");
        if (cur == null) return "";
        return place(c) + " · " + Math.round(cur.optDouble("temperature_2m")) + "° · " + words(cur.optInt("weather_code"));
    }

    static JSONArray arr(JSONObject o, String k) { JSONArray a = o == null ? null : o.optJSONArray(k); return a == null ? new JSONArray() : a; }
}
