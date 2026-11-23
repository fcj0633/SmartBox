package cn.smartbox.enterprise;

import java.util.List;
import org.springframework.stereotype.Component;
import cn.smartbox.enterprise.EnterpriseViews.Point;
import cn.smartbox.enterprise.EnterpriseViews.Position;
import cn.smartbox.enterprise.EnterpriseViews.Route;

/**
 * Read-time deterministic projection. No scheduler, no random coordinates and no location table.
 * READY = origin, ACTIVE = interpolate from server time, COMPLETED = fixed end, offline = freeze.
 */
@Component
public class MockPositionProjector {
    public Position project(Route route, String status, Long startedAt, Long endedAt,
                            long now, boolean online, Long lastSeen) {
        if (route == null || route.points() == null || route.points().isEmpty()) return null;
        String state = status == null ? "READY" : status;
        long duration = Math.max(1, route.duration_ms());
        long positionAt;
        double progress;
        boolean stale = false;
        boolean finished = false;
        if ("READY".equals(state)) {
            positionAt = now;
            progress = 0;
        } else if ("COMPLETED".equals(state)) {
            long end = endedAt == null ? (startedAt == null ? now : startedAt + duration) : endedAt;
            long start = startedAt == null ? end : startedAt;
            positionAt = end;
            progress = clamp((double) (end - start) / duration);
            finished = progress >= 1;
        } else if ("ACTIVE".equals(state)) {
            long start = startedAt == null ? now : startedAt;
            long effectiveNow = online ? now : (lastSeen == null ? Math.min(now, start) : Math.min(now, lastSeen));
            positionAt = effectiveNow;
            progress = clamp((double) (effectiveNow - start) / duration);
            finished = now >= start + duration;
            stale = !online;
        } else {
            positionAt = now;
            progress = 0;
        }
        Interpolated point = interpolate(route.points(), progress);
        return new Position(round(point.x()), round(point.y()), point.place(), route.code(), route.name(),
            round(progress), positionAt, stale, finished, "MOCK");
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static double round(double value) { return Math.round(value * 1000d) / 1000d; }

    private record Interpolated(double x, double y, String place) {}

    private static Interpolated interpolate(List<Point> points, double progress) {
        if (points.size() == 1) return new Interpolated(points.getFirst().x(), points.getFirst().y(), points.getFirst().place());
        double total = 0;
        double[] lengths = new double[points.size() - 1];
        for (int i = 0; i < lengths.length; i++) {
            lengths[i] = Math.hypot(points.get(i + 1).x() - points.get(i).x(), points.get(i + 1).y() - points.get(i).y());
            total += lengths[i];
        }
        if (total <= 0) return new Interpolated(points.getFirst().x(), points.getFirst().y(), points.getFirst().place());
        double target = clamp(progress) * total;
        if (target <= 0) return new Interpolated(points.getFirst().x(), points.getFirst().y(), points.getFirst().place());
        if (target >= total) return new Interpolated(points.getLast().x(), points.getLast().y(), points.getLast().place());
        double walked = 0;
        for (int i = 0; i < lengths.length; i++) {
            if (walked + lengths[i] >= target) {
                double ratio = lengths[i] <= 0 ? 0 : (target - walked) / lengths[i];
                Point a = points.get(i), b = points.get(i + 1);
                return new Interpolated(a.x() + (b.x() - a.x()) * ratio, a.y() + (b.y() - a.y()) * ratio,
                    ratio < 0.5 ? a.place() : b.place());
            }
            walked += lengths[i];
        }
        Point last = points.getLast();
        return new Interpolated(last.x(), last.y(), last.place());
    }
}
