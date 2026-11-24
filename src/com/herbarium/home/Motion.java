package com.herbarium.home;

/**
 * How Home's sheet glides to rest after a swipe: it leaves at the finger's speed, slows as if it had weight, and
 * arrives exactly on the page at rest. Its speed only ever falls, so it can never pass the page or spring back.
 * Pure arithmetic (checked in tests/Checks.java).
 */
final class Motion {
    private Motion() {}

    /** A planned glide: how long it takes (ms), and the speed it leaves with (px/ms, 0 = the calm ease). */
    static final class Glide { final long ms; final float speed; Glide(long ms, float speed) { this.ms = ms; this.speed = speed; } }

    /** Plan a glide over `distance` px, leaving at `fingerSpeed` px/ms toward the page (≤ 0: none), on a screen `width` px wide. */
    static Glide plan(float distance, float fingerSpeed, float width) {
        float v0 = Math.max(0, fingerSpeed);
        if (distance < 1 || v0 * 700 < distance)                                 // a slow release: the calm ease
            return new Glide(Math.round(380 + 220 * Math.min(1f, distance / Math.max(1, width))), 0);
        long ms = Math.round(Math.max(320, Math.min(650, 2 * distance / v0)));   // weight: slowing as if braking evenly
        return new Glide(ms, Math.min(v0, 3 * distance / ms));                   // never so fast that it would pass the page
    }

    /** How far along the glide it is at fraction u (0..1) of the time, in px (0..distance). */
    static float travelled(Glide g, float distance, float u, android.view.animation.Interpolator calm) {
        if (g.speed <= 0) return distance * (calm == null ? smooth(u) : calm.getInterpolation(u));
        float h10 = u * u * u - 2 * u * u + u, h01 = -2 * u * u * u + 3 * u * u;
        return Math.min(distance, h10 * g.ms * g.speed + h01 * distance);
    }

    private static float smooth(float u) { return u * u * (3 - 2 * u); }
}
