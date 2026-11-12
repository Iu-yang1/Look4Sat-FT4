import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import org.orekit.data.DirectoryCrawler;
import org.orekit.data.LazyLoadedDataContext;
import org.orekit.bodies.CelestialBody;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.IERSConventions;

/** Offline validation against JPL's independent TESTPO coefficient checks. */
class VerifyOrekitData {
    public static void main(String[] args) throws Exception {
        var context = new LazyLoadedDataContext();
        context.getDataProvidersManager().addProvider(new DirectoryCrawler(Path.of(args[0]).toFile()));
        var bodies = context.getCelestialBodies();
        var utc = context.getTimeScales().getUTC();
        var tdb = context.getTimeScales().getTDB();
        var reference = context.getFrames().getICRF();
        var manifest = Files.readString(Path.of(args[0], "manifest.json"));
        var endMatcher = java.util.regex.Pattern.compile("\"eopEndMillis\"\\s*:\\s*(\\d+)").matcher(manifest);
        if (!endMatcher.find()) throw new IllegalStateException("Missing EOP coverage");
        long declaredEopEnd = Long.parseLong(endMatcher.group(1));
        long actualEopEnd = context.getFrames().getEOPHistory(IERSConventions.IERS_2010, false)
            .getEndDate().toDate(utc).getTime();
        if (Math.abs(declaredEopEnd - actualEopEnd) > 86400000L) {
            throw new IllegalStateException("EOP coverage mismatch: "+declaredEopEnd+" / "+actualEopEnd);
        }
        Map<Integer, CelestialBody> targets = new HashMap<>();
        targets.put(1, bodies.getMercury()); targets.put(2, bodies.getVenus());
        targets.put(3, bodies.getEarth()); targets.put(4, bodies.getMars());
        targets.put(5, bodies.getJupiter()); targets.put(6, bodies.getSaturn());
        targets.put(7, bodies.getUranus()); targets.put(8, bodies.getNeptune());
        targets.put(9, bodies.getPluto()); targets.put(10, bodies.getMoon());
        targets.put(11, bodies.getSun()); targets.put(12, bodies.getSolarSystemBarycenter());
        targets.put(13, bodies.getEarthMoonBarycenter());
        for (String day : new String[]{"2000-01-01T00:00:00", "2050-01-01T00:00:00", "2100-12-31T23:59:59"}) {
            var date = new AbsoluteDate(day, utc);
            for (var body : targets.values()) {
                var norm = body.getPosition(date, reference).getNorm();
                if (!Double.isFinite(norm)) throw new IllegalStateException(day);
            }
            context.getFrames().getITRF(IERSConventions.IERS_2010, false).getTransformTo(reference, date);
        }
        int checked = 0;
        double maxError = 0;
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            String[] cells = line.trim().split("\\s+");
            if (cells.length != 7 || !cells[0].equals("440")) continue;
            double jd = Double.parseDouble(cells[2]);
            if (jd < 2451544.5 || jd >= 2488434.5) continue;
            int target = Integer.parseInt(cells[3]), center = Integer.parseInt(cells[4]);
            int component = Integer.parseInt(cells[5]);
            if (!targets.containsKey(target) || !targets.containsKey(center) || component < 1 || component > 6) continue;
            int integer = (int)Math.floor(jd);
            var date = AbsoluteDate.createJDDate(integer, (jd-integer)*86400, tdb);
            var delta = new org.orekit.utils.PVCoordinates(
                targets.get(center).getPVCoordinates(date, reference),
                targets.get(target).getPVCoordinates(date, reference));
            var vector = component <= 3 ? delta.getPosition() : delta.getVelocity().scalarMultiply(86400);
            double actual = vector.toArray()[(component-1)%3] / 149597870700.0;
            double error = Math.abs(actual-Double.parseDouble(cells[6]));
            if (error > 2e-11) throw new IllegalStateException("JPL check failed: "+line+" error="+error);
            maxError = Math.max(maxError, error);
            checked++;
        }
        if (checked < 500) throw new IllegalStateException("Insufficient JPL checks: "+checked);
        System.out.println("JPL TESTPO checks="+checked+" maxErrorAUorAUPerDay="+maxError+" coverage=2000-2100");
    }
}
