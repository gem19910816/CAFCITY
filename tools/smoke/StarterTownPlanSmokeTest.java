import com.caf.lccustomcity.impl.StarterTownPlan;
import java.util.List;

/** Pure deterministic checks for the bounded nearby-town selector. */
public final class StarterTownPlanSmokeTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        List<StarterTownPlan.Point> points = StarterTownPlan.candidates(1234L, 0, 0, 600, 900);
        check(!points.isEmpty(), "ring must contain aligned points");
        for (var p : points) {
            double distance = Math.hypot(p.x(), p.z());
            check(distance >= 600 && distance <= 900, "candidate outside requested ring");
            check(p.x() % 16 == 0 && p.z() % 16 == 0, "city centers must be chunk aligned");
        }
        check(points.equals(StarterTownPlan.candidates(1234L, 0, 0, 600, 900)),
                "same seed must produce the same order");
        var selected = StarterTownPlan.select(points, p -> true, p -> p.x() == points.get(0).x()
                && p.z() == points.get(0).z(), false);
        check(selected != null && !selected.earthworks(), "eligible terrain should win without earthworks");
        var fallback = StarterTownPlan.select(points, p -> true, p -> false, true);
        check(fallback != null && fallback.earthworks(), "enabled flattening gives explicit fallback");
        check(StarterTownPlan.select(points, p -> false, p -> true, true) == null,
                "no eligible point must not fake a reservation");
        check(StarterTownPlan.separated(0, 0, 200, 600, 0, 200), "600-center pair leaves edge gap");
        check(!StarterTownPlan.separated(0, 0, 300, 600, 0, 300), "overlapping pair must be rejected");
        System.out.println("Starter town plan: PASS (bounded ring, deterministic order, terrain fallback, separation)");
    }
}
