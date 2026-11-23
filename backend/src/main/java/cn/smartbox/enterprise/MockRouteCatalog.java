package cn.smartbox.enterprise;

import java.util.List;
import org.springframework.stereotype.Component;
import cn.smartbox.enterprise.EnterpriseViews.Point;
import cn.smartbox.enterprise.EnterpriseViews.Route;

/** Three deterministic DEMO routes in normalized canvas coordinates. Never treated as GPS. */
@Component
public class MockRouteCatalog {
    private static final List<Route> ROUTES = List.of(
        new Route("EAST-01", "虹桥 → 苏州工业园区", "上海 · 虹桥", "苏州 · 工业园区", 240_000,
            List.of(new Point(30, 68, "上海 · 虹桥"), new Point(38, 61, "上海 · 嘉定"),
                new Point(47, 54, "昆山 · 花桥"), new Point(55, 47, "苏州 · 相城"),
                new Point(63, 40, "苏州 · 姑苏"), new Point(69, 35, "苏州 · 工业园区"))),
        new Route("EAST-02", "钱江新城 → 嘉兴南湖", "杭州 · 钱江新城", "嘉兴 · 南湖", 300_000,
            List.of(new Point(52, 80, "杭州 · 钱江新城"), new Point(48, 72, "杭州 · 临平"),
                new Point(43, 64, "桐乡 · 乌镇"), new Point(38, 56, "嘉兴 · 秀洲"),
                new Point(34, 49, "嘉兴 · 南湖"))),
        new Route("NORTH-01", "江宁 → 无锡新吴", "南京 · 江宁", "无锡 · 新吴", 270_000,
            List.of(new Point(31, 20, "南京 · 江宁"), new Point(39, 25, "南京 · 栖霞"),
                new Point(48, 29, "镇江 · 句容"), new Point(57, 32, "常州 · 溧阳"),
                new Point(65, 36, "无锡 · 新吴")))
    );

    public List<Route> routes() { return ROUTES; }

    public Route byCode(String code) {
        return ROUTES.stream().filter(route -> route.code().equals(code)).findFirst().orElse(null);
    }
}
