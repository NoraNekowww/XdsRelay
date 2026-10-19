package com.nz.xdsrelay;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * Generate FIT variants with the SAME writer as the phone, to find out which
 * property makes the online parser (nzlab.online/api/upload) return HTTP 500.
 *
 * usage: java com.nz.xdsrelay.GenVariants <outDir>
 *   -> full.fit     600 records, everything present
 *      nofix.fit    600 records, NO gps fix (lat/lon invalid)
 *      nopower.fit  600 records, no power/cadence (sensor not paired)
 *      big.fit      12000 records (~3h20m, ~330KB)
 *      tiny.fit     3 records
 */
public class GenVariants {

    static final long START = 1790000000L;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        gen(new File(dir, "full.fit"), 600, true, true);
        gen(new File(dir, "nofix.fit"), 600, false, true);
        gen(new File(dir, "nopower.fit"), 600, true, false);
        gen(new File(dir, "big.fit"), 12000, true, true);
        gen(new File(dir, "tiny.fit"), 3, true, true);
        System.out.println("done -> " + dir.getAbsolutePath());
    }

    static void gen(File out, int n, boolean fix, boolean sensors) throws Exception {
        ByteArrayOutputStream rec = new ByteArrayOutputStream(n * 40 + 1024);
        byte[] def = FitWriter.recordDefinition();
        rec.write(def, 0, def.length);

        FitWriter.Summary s = new FitWriter.Summary();
        s.startEpochSec = START;

        double dist = 0, timer = 0, elapsed = 0, maxSpd = 0, spdSum = 0;
        int maxPow = 0, maxCad = 0;
        long powSum = 0, cadSum = 0, powN = 0, cadN = 0;
        double maxAlt = Double.NaN, minAlt = Double.NaN, prevAlt = Double.NaN;
        int asc = 0, desc = 0;
        long lastTs = START + 1;

        for (int i = 0; i < n; i++) {
            double lat = 39.9042 + i * 0.0001;
            double lon = 116.4074 + i * 0.00015;
            double alt = 43.0 + 5.0 * Math.sin(i / 50.0);
            double spd = 6.0 + 2.0 * Math.sin(i / 30.0);
            int cad = 80 + (i % 20);
            int pow = 150 + (i * 7) % 200;

            dist += spd;
            elapsed += 1;
            if (spd > 0.8) timer += 1;
            spdSum += spd;
            if (spd > maxSpd) maxSpd = spd;
            if (sensors) {
                powSum += pow; powN++;
                if (pow > maxPow) maxPow = pow;
                cadSum += cad; cadN++;
                if (cad > maxCad) maxCad = cad;
            }
            if (Double.isNaN(maxAlt) || alt > maxAlt) maxAlt = alt;
            if (Double.isNaN(minAlt) || alt < minAlt) minAlt = alt;
            if (!Double.isNaN(prevAlt)) {
                double d = alt - prevAlt;
                if (d > 1) asc += (int) d; else if (d < -1) desc += (int) -d;
            }
            prevAlt = alt;

            FitWriter.Sample p = new FitWriter.Sample();
            p.timestampSec = START + i + 1;
            p.lat = fix ? lat : Double.NaN;
            p.lon = fix ? lon : Double.NaN;
            p.altitudeM = alt;
            p.distanceM = dist;
            p.speedMps = spd;
            p.cadence = sensors ? cad : -1;
            p.power = sensors ? pow : -1;
            p.gpsAccuracyM = fix ? 4 : -1;
            byte[] b = FitWriter.recordData(p);
            rec.write(b, 0, b.length);
            lastTs = p.timestampSec;
        }

        s.endEpochSec = lastTs;
        s.totalElapsedSec = elapsed;
        s.totalTimerSec = timer;
        s.totalDistanceM = dist;
        s.avgSpeedMps = timer > 1 ? dist / timer : 0;
        s.maxSpeedMps = maxSpd;
        s.avgCadence = cadN > 0 ? (int) (cadSum / cadN) : 0;
        s.maxCadence = maxCad;
        s.avgPower = powN > 0 ? (int) (powSum / powN) : 0;
        s.maxPower = maxPow;
        s.normalizedPower = s.avgPower;
        s.maxAltitude = maxAlt;
        s.minAltitude = minAlt;
        s.totalAscent = asc;
        s.totalDescent = desc;
        s.totalCalories = (int) (timer / 3600.0 * 520);

        byte[] fit = FitWriter.build(rec.toByteArray(), s);
        FitWriter.writeToFile(fit, out);
        System.out.printf("%-12s %7d bytes  %d records  fix=%b sensors=%b%n",
                out.getName(), fit.length, n, fix, sensors);
    }
}
