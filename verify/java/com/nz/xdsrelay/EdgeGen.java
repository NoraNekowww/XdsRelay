package com.nz.xdsrelay;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * Edge-case FIT generator (SAME writer as the phone) to hunt the HTTP 500.
 * usage: java com.nz.xdsrelay.EdgeGen <outDir>
 */
public class EdgeGen {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        // 1) zero records, zero summary -> elapsed/timer/distance all 0
        gen(dir, "e_zero.fit", 0, 1790000000L, true, true, true, 0.0);

        // 2) one record, elapsed==0 (start==end)
        gen(dir, "e_onerec_zerotime.fit", 1, 1790000000L, true, true, true, 0.0);

        // 3) zero elapsed but 600 records (summary degenerate, records fine)
        gen(dir, "e_zeroelapsed.fit", 600, 1790000000L, true, true, true, 0.0);

        // 4) timestamp far future (fitTs > 2^31 signed overflow), year 2100
        gen(dir, "e_ts_2100.fit", 600, 4102444800L, true, true, true, 0.0);

        // 5) timestamp just past 2038 (fitTs ~ 2^31 boundary)
        gen(dir, "e_ts_2038.fit", 600, 2200000000L, true, true, true, 0.0);

        // 6) no fix + no sensors, distance 0 (fully invalid records)
        gen(dir, "e_all_invalid.fit", 600, 1790000000L, false, false, false, 0.0);

        // 7) fix but speed==0 and distance==0
        gen(dir, "e_zerospeed.fit", 600, 1790000000L, true, true, false, 0.0);

        // 8) coasting: cadence==0 while moving  (gear_ratio = speed/cadence -> div0?)
        genCad(dir, "e_cad0.fit", 600, 1790000000L, 0, 150);

        // 9) cadence==0 AND power==0 while moving
        genCad(dir, "e_cad0_pow0.fit", 600, 1790000000L, 0, 0);

        // 10) cadence invalid (0xFF) while moving
        genCad(dir, "e_cadinv.fit", 600, 1790000000L, -1, 150);

        System.out.println("done -> " + dir.getAbsolutePath());
    }

    /**
     * @param n        records
     * @param startEpoch start (UTC sec)
     * @param fix      write GPS
     * @param sensors  write power/cadence
     * @param moving   speed>0 (affects timer/distance)
     * @param speedOverride <0 keep generated speed; >=0 force this speed
     */
    static void gen(File dir, String name, int n, long startEpoch,
                    boolean fix, boolean sensors, boolean moving, double speedOverride) throws Exception {
        ByteArrayOutputStream rec = new ByteArrayOutputStream(n * 40 + 1024);
        byte[] def = FitWriter.recordDefinition();
        rec.write(def, 0, def.length);

        FitWriter.Summary s = new FitWriter.Summary();
        s.startEpochSec = startEpoch;

        double dist = 0, timer = 0, elapsed = 0, maxSpd = 0, spdSum = 0;
        int maxPow = 0, maxCad = 0;
        long powSum = 0, cadSum = 0, powN = 0, cadN = 0;
        double maxAlt = Double.NaN, minAlt = Double.NaN;
        long lastTs = startEpoch;

        for (int i = 0; i < n; i++) {
            double lat = 39.9042 + i * 0.0001;
            double lon = 116.4074 + i * 0.00015;
            double alt = 43.0 + 5.0 * Math.sin(i / 50.0);
            double spd = (speedOverride >= 0) ? speedOverride
                    : (moving ? 6.0 + 2.0 * Math.sin(i / 30.0) : 0.0);
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

            FitWriter.Sample p = new FitWriter.Sample();
            p.timestampSec = startEpoch + i + 1;
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

        if (n == 0) { // degenerate summary
            s.endEpochSec = startEpoch;
            s.totalElapsedSec = 0; s.totalTimerSec = 0; s.totalDistanceM = 0;
            s.avgSpeedMps = 0; s.maxSpeedMps = 0;
            s.avgCadence = 0; s.maxCadence = 0; s.avgPower = 0; s.maxPower = 0; s.normalizedPower = 0;
            s.maxAltitude = Double.NaN; s.minAltitude = Double.NaN;
            s.totalAscent = 0; s.totalDescent = 0; s.totalCalories = 0;
        } else {
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
            s.maxAltitude = maxAlt; s.minAltitude = minAlt;
            s.totalAscent = 0; s.totalDescent = 0;
            s.totalCalories = (int) (timer / 3600.0 * 520);
        }

        byte[] fit = FitWriter.build(rec.toByteArray(), s);
        FitWriter.writeToFile(fit, new File(dir, name));
        System.out.printf("%-22s %7d bytes  n=%d start=%d%n", name, fit.length, n, startEpoch);
    }

    /** Moving records with a forced cadence (0 = coasting, -1 = invalid) and forced power. */
    static void genCad(File dir, String name, int n, long startEpoch,
                       int cadence, int power) throws Exception {
        ByteArrayOutputStream rec = new ByteArrayOutputStream(n * 40 + 1024);
        byte[] def = FitWriter.recordDefinition();
        rec.write(def, 0, def.length);

        FitWriter.Summary s = new FitWriter.Summary();
        s.startEpochSec = startEpoch;

        double dist = 0, timer = 0, elapsed = 0, maxSpd = 0;
        double maxAlt = Double.NaN, minAlt = Double.NaN;
        long lastTs = startEpoch;

        for (int i = 0; i < n; i++) {
            double lat = 39.9042 + i * 0.0001;
            double lon = 116.4074 + i * 0.00015;
            double alt = 43.0 + 5.0 * Math.sin(i / 50.0);
            double spd = 6.0 + 2.0 * Math.sin(i / 30.0);

            dist += spd;
            elapsed += 1;
            if (spd > 0.8) timer += 1;
            if (spd > maxSpd) maxSpd = spd;
            if (Double.isNaN(maxAlt) || alt > maxAlt) maxAlt = alt;
            if (Double.isNaN(minAlt) || alt < minAlt) minAlt = alt;

            FitWriter.Sample p = new FitWriter.Sample();
            p.timestampSec = startEpoch + i + 1;
            p.lat = lat; p.lon = lon;
            p.altitudeM = alt;
            p.distanceM = dist;
            p.speedMps = spd;
            p.cadence = cadence;
            p.power = power;
            p.gpsAccuracyM = 4;
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
        s.avgCadence = cadence < 0 ? 0 : cadence;
        s.maxCadence = cadence < 0 ? 0 : cadence;
        s.avgPower = power;
        s.maxPower = power;
        s.normalizedPower = power;
        s.maxAltitude = maxAlt; s.minAltitude = minAlt;
        s.totalAscent = 0; s.totalDescent = 0;
        s.totalCalories = (int) (timer / 3600.0 * 520);

        byte[] fit = FitWriter.build(rec.toByteArray(), s);
        FitWriter.writeToFile(fit, new File(dir, name));
        System.out.printf("%-22s %7d bytes  n=%d cad=%d pow=%d%n", name, fit.length, n, cadence, power);
    }
}
