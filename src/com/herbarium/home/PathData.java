package com.herbarium.home;

import android.graphics.Path;

/**
 * Turns SVG path strings ("M0 0 C 9 -14 ...") into Android Paths, so the engravings can be written
 * exactly as they were in the web version. Supports M L H V C S Q T Z in absolute and relative form.
 */
final class PathData {
    private PathData() {}

    static Path parse(String d) {
        Path p = new Path();
        int i = 0, n = d.length();
        char cmd = 'M';
        float x = 0, y = 0, sx = 0, sy = 0, lcx = 0, lcy = 0;   // current point, subpath start, last control point
        char last = ' ';
        float[] a = new float[6];
        while (i < n) {
            char c = d.charAt(i);
            if (Character.isLetter(c)) { cmd = c; i++; }
            else if (c == ' ' || c == ',' || c == '\n' || c == '\t') { i++; continue; }
            boolean rel = Character.isLowerCase(cmd);
            char up = Character.toUpperCase(cmd);
            if (up == 'Z') { p.close(); x = sx; y = sy; last = 'Z'; continue; }
            int need = up == 'C' ? 6 : (up == 'S' || up == 'Q') ? 4 : (up == 'H' || up == 'V') ? 1 : 2;
            for (int k = 0; k < need; k++) {
                // skip separators
                while (i < n && (d.charAt(i) == ' ' || d.charAt(i) == ',' || d.charAt(i) == '\n')) i++;
                int st = i;
                if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
                boolean dot = false;
                while (i < n) {
                    char ch = d.charAt(i);
                    if (Character.isDigit(ch)) i++;
                    else if (ch == '.' && !dot) { dot = true; i++; }
                    else if ((ch == 'e' || ch == 'E') && i + 1 < n) { i++; if (d.charAt(i) == '-' || d.charAt(i) == '+') i++; }
                    else break;
                }
                a[k] = i > st ? Float.parseFloat(d.substring(st, i)) : 0f;
            }
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (up) {
                case 'M':
                    x = a[0] + ox; y = a[1] + oy; p.moveTo(x, y); sx = x; sy = y;
                    cmd = rel ? 'l' : 'L';   // further pairs are line-tos
                    break;
                case 'L': x = a[0] + ox; y = a[1] + oy; p.lineTo(x, y); break;
                case 'H': x = a[0] + ox; p.lineTo(x, y); break;
                case 'V': y = a[0] + (rel ? y : 0); p.lineTo(x, y); break;
                case 'C':
                    p.cubicTo(a[0] + ox, a[1] + oy, a[2] + ox, a[3] + oy, a[4] + ox, a[5] + oy);
                    lcx = a[2] + ox; lcy = a[3] + oy; x = a[4] + ox; y = a[5] + oy; break;
                case 'S': {
                    float c1x = (last == 'C' || last == 'S') ? 2 * x - lcx : x, c1y = (last == 'C' || last == 'S') ? 2 * y - lcy : y;
                    p.cubicTo(c1x, c1y, a[0] + ox, a[1] + oy, a[2] + ox, a[3] + oy);
                    lcx = a[0] + ox; lcy = a[1] + oy; x = a[2] + ox; y = a[3] + oy; break;
                }
                case 'Q':
                    p.quadTo(a[0] + ox, a[1] + oy, a[2] + ox, a[3] + oy);
                    lcx = a[0] + ox; lcy = a[1] + oy; x = a[2] + ox; y = a[3] + oy; break;
                case 'T': {
                    float c1x = (last == 'Q' || last == 'T') ? 2 * x - lcx : x, c1y = (last == 'Q' || last == 'T') ? 2 * y - lcy : y;
                    p.quadTo(c1x, c1y, a[0] + ox, a[1] + oy);
                    lcx = c1x; lcy = c1y; x = a[0] + ox; y = a[1] + oy; break;
                }
                default: i++;   // unknown command: skip a character so we never loop forever
            }
            last = up;
        }
        return p;
    }
}
