package com.herbarium.home;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** In-memory copy of what's blocked, so each lookup is a few hash checks. Rebuilt when the computer sends changes. */
final class Lists {
    final Set<String> domains, allow, feed;
    final Map<String, String> safe;
    private static volatile Lists cur;
    private static volatile Set<String> feedSet = Collections.emptySet();
    private static volatile long feedLoaded = -1;

    private Lists(Set<String> d, Set<String> a, Set<String> f, Map<String, String> s) { domains = d; allow = a; feed = f; safe = s; }

    static void invalidate() { cur = null; }

    static File feedFile(Context c) { return new File(Theme.store(c).getFilesDir(), "feed.txt"); }

    static Lists get(Context c) {
        Lists l = cur;
        if (l != null) return l;
        synchronized (Lists.class) {
            if (cur != null) return cur;
            Map<String, String> safe = new HashMap<>();
            try {
                JSONObject o = new JSONObject(State.prefs(c).getString("safe", "{}"));
                for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); safe.put(k.toLowerCase(), o.getString(k)); }
            } catch (Exception ignored) {}
            boolean wantFeed = !State.prefs(c).getString("feedWant", "").isEmpty();
            cur = new Lists(State.domains(c), new HashSet<>(State.prefs(c).getStringSet("allow", new HashSet<>())),
                    wantFeed ? loadFeed(c) : Collections.emptySet(), safe);
            return cur;
        }
    }

    private static Set<String> loadFeed(Context c) {
        File f = feedFile(c);
        if (!f.exists()) return Collections.emptySet();
        if (f.lastModified() == feedLoaded) return feedSet;
        Set<String> s = new HashSet<>(100_000);
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) { line = line.trim(); if (!line.isEmpty()) s.add(line); }
        } catch (Exception ignored) {}
        feedSet = s; feedLoaded = f.lastModified();
        return s;
    }
}
