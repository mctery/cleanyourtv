package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class XmbWeatherTest {
    @Test public void clearSkyBySunAndMoon() {
        assertEquals(R.drawable.ic_xmb_sunny, XmbWeather.icon(0, true));
        assertEquals(R.drawable.ic_xmb_clear_night, XmbWeather.icon(0, false));
        assertEquals(R.drawable.ic_xmb_partly_cloudy_day, XmbWeather.icon(2, true));
        assertEquals(R.drawable.ic_xmb_partly_cloudy_night, XmbWeather.icon(1, false));
    }

    @Test public void wmoGroups() {
        assertEquals(R.drawable.ic_xmb_cloud, XmbWeather.icon(3, true));
        assertEquals(R.drawable.ic_xmb_foggy, XmbWeather.icon(45, true));
        assertEquals(R.drawable.ic_xmb_rainy, XmbWeather.icon(53, true));
        assertEquals(R.drawable.ic_xmb_rainy, XmbWeather.icon(65, false));
        assertEquals(R.drawable.ic_xmb_rainy, XmbWeather.icon(81, true));
        assertEquals(R.drawable.ic_xmb_weather_snowy, XmbWeather.icon(73, true));
        assertEquals(R.drawable.ic_xmb_thunderstorm, XmbWeather.icon(95, true));
        assertEquals(R.drawable.ic_xmb_cloud, XmbWeather.icon(12345, true));   // unknown: a cloud, not a guess
    }

    @Test public void onlyRoundedCoordinatesAreSent() {
        assertEquals(13.76, XmbWeather.round(13.756789), 1e-9);
        assertEquals(-0.13, XmbWeather.round(-0.1301), 1e-9);
        String url = XmbWeather.url(13.756789, 100.501234);
        assertTrue(url, url.contains("latitude=13.76&longitude=100.50&"));
        assertTrue(url, url.startsWith("https://api.open-meteo.com/"));
    }

    @Test public void storedAnswerRoundTrips() {
        XmbWeather w = XmbWeather.decode(XmbWeather.encode(new XmbWeather(31, 2, true, 1234567890L)));
        assertEquals(31, w.temperature);
        assertEquals(2, w.code);
        assertTrue(w.day);
        assertEquals(1234567890L, w.time);
        assertNull(XmbWeather.decode("31|2|1"));
        assertNull(XmbWeather.decode("a|b|c|d"));
    }
}
