package com.nz.xdsrelay;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * 离线验证 FitWriter：生成一份"已知内容"的骑行记录，
 * 把期望值写成 expected.json，交给 Python + fitdecode 回读断言。
 */
public class FitCheck {

    static final int N = 600;
    static final long START = 1790000000L;      // 固定起点，保证可复现

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : ".";
        File fit = new File(outDir, "gen_test.fit");
        File exp = new File(outDir, "expected.json");

        ByteArrayOutputStream records = new ByteArrayOutputStream(64 * 1024);
        byte[] def = FitWriter.recordDefinition();
        records.write(def, 0, def.length);

        FitWriter.Summary s = new FitWriter.Summary();
        s.startEpochSec = START;

        double dist = 0;
        double timer = 0, elapsed = 0;
        double maxSpeed = 0, speedSum = 0;
        int maxPower = 0; long powerSum = 0;
        int maxCad = 0; long cadSum = 0;
        double maxAlt = Double.NaN, minAlt = Double.NaN;
        int ascent = 0, descent = 0;
        double prevAlt = Double.NaN;
        double lastSpeed = 0, lastAlt = 0;
        int lastCad = 0, lastPower = 0;
        double lastLat = 0, lastLon = 0, lastDist = 0;
        long lastTs = 0;

        int[] powers = new int[N];

        for (int i = 0; i < N; i++) {
            double lat = 39.9042 + i * 0.0001;
            double lon = 116.4074 + i * 0.00015;
            double alt = 43.0 + 10.0 * Math.sin(i / 50.0);
            double speed = 6.0 + 2.0 * Math.sin(i / 30.0);
            int cadence = 80 + (i % 20);
            int power = 150 + (i * 7) % 200;

            dist += speed * 1.0;
            elapsed += 1.0;
            if (speed > 0.8) timer += 1.0;

            speedSum += speed;
            if (speed > maxSpeed) maxSpeed = speed;
            powerSum += power; powers[i] = power;
            if (power > maxPower) maxPower = power;
            cadSum += cadence;
            if (cadence > maxCad) maxCad = cadence;
            if (Double.isNaN(maxAlt) || alt > maxAlt) maxAlt = alt;
            if (Double.isNaN(minAlt) || alt < minAlt) minAlt = alt;
            if (!Double.isNaN(prevAlt)) {
                double d = alt - prevAlt;
                if (d > 1.0) ascent += (int) d;
                else if (d < -1.0) descent += (int) -d;
            }
            prevAlt = alt;

            FitWriter.Sample p = new FitWriter.Sample();
            p.timestampSec = START + i + 1;
            p.lat = lat;
            p.lon = lon;
            p.altitudeM = alt;
            p.distanceM = dist;
            p.speedMps = speed;
            p.cadence = cadence;
            p.power = power;
            p.gpsAccuracyM = 4;
            byte[] d = FitWriter.recordData(p);
            records.write(d, 0, d.length);

            lastTs = p.timestampSec;
            lastLat = lat; lastLon = lon; lastAlt = alt;
            lastSpeed = speed; lastCad = cadence; lastPower = power; lastDist = dist;
        }

        s.endEpochSec = lastTs;
        s.totalElapsedSec = elapsed;
        s.totalTimerSec = timer;
        s.totalDistanceM = dist;
        s.avgSpeedMps = dist / timer;
        s.maxSpeedMps = maxSpeed;
        s.avgCadence = (int) (cadSum / N);
        s.maxCadence = maxCad;
        s.avgPower = (int) (powerSum / N);
        s.maxPower = maxPower;
        s.normalizedPower = np(powers);
        s.maxAltitude = maxAlt;
        s.minAltitude = minAlt;
        s.totalAscent = ascent;
        s.totalDescent = descent;
        s.totalCalories = (int) (timer / 3600.0 * 520);

        byte[] out = FitWriter.build(records.toByteArray(), s);
        FitWriter.writeToFile(out, fit);

        // ---- 期望值 ----
        try (PrintWriter w = new PrintWriter(exp, "UTF-8")) {
            w.println("{");
            w.printf(Locale.ROOT, "  \"records\": %d,%n", N);
            w.printf(Locale.ROOT, "  \"start_epoch\": %d,%n", START + 1);
            w.printf(Locale.ROOT, "  \"end_epoch\": %d,%n", lastTs);
            w.printf(Locale.ROOT, "  \"first_lat\": %.7f,%n", 39.9042);
            w.printf(Locale.ROOT, "  \"first_lon\": %.7f,%n", 116.4074);
            w.printf(Locale.ROOT, "  \"last_lat\": %.7f,%n", lastLat);
            w.printf(Locale.ROOT, "  \"last_lon\": %.7f,%n", lastLon);
            w.printf(Locale.ROOT, "  \"last_alt\": %.3f,%n", lastAlt);
            w.printf(Locale.ROOT, "  \"last_speed\": %.4f,%n", lastSpeed);
            w.printf(Locale.ROOT, "  \"last_cadence\": %d,%n", lastCad);
            w.printf(Locale.ROOT, "  \"last_power\": %d,%n", lastPower);
            w.printf(Locale.ROOT, "  \"last_distance\": %.2f,%n", lastDist);
            w.printf(Locale.ROOT, "  \"total_distance\": %.2f,%n", s.totalDistanceM);
            w.printf(Locale.ROOT, "  \"total_timer\": %.1f,%n", s.totalTimerSec);
            w.printf(Locale.ROOT, "  \"total_elapsed\": %.1f,%n", s.totalElapsedSec);
            w.printf(Locale.ROOT, "  \"avg_speed\": %.4f,%n", s.avgSpeedMps);
            w.printf(Locale.ROOT, "  \"max_speed\": %.4f,%n", s.maxSpeedMps);
            w.printf(Locale.ROOT, "  \"avg_cadence\": %d,%n", s.avgCadence);
            w.printf(Locale.ROOT, "  \"max_cadence\": %d,%n", s.maxCadence);
            w.printf(Locale.ROOT, "  \"avg_power\": %d,%n", s.avgPower);
            w.printf(Locale.ROOT, "  \"max_power\": %d,%n", s.maxPower);
            w.printf(Locale.ROOT, "  \"np\": %d,%n", s.normalizedPower);
            w.printf(Locale.ROOT, "  \"max_alt\": %.3f,%n", s.maxAltitude);
            w.printf(Locale.ROOT, "  \"min_alt\": %.3f,%n", s.minAltitude);
            w.printf(Locale.ROOT, "  \"ascent\": %d,%n", s.totalAscent);
            w.printf(Locale.ROOT, "  \"descent\": %d%n", s.totalDescent);
            w.println("}");
        }

        System.out.printf(Locale.ROOT, "生成 %s  %d 字节, %d 条记录%n", fit.getAbsolutePath(), out.length, N);
        System.out.printf(Locale.ROOT, "期望: 里程 %.2f m, 运动 %.1f s, 均速 %.3f m/s, 均功率 %d W, NP %d W%n",
                s.totalDistanceM, s.totalTimerSec, s.avgSpeedMps, s.avgPower, s.normalizedPower);
    }

    /** 与 FitRecorder 相同的 NP 算法 */
    static int np(int[] p) {
        if (p.length < 30) {
            long sum = 0;
            for (int v : p) sum += v;
            return (int) (sum / Math.max(1, p.length));
        }
        double sum4 = 0;
        int n = 0;
        long window = 0;
        for (int i = 0; i < p.length; i++) {
            window += p[i];
            if (i >= 30) window -= p[i - 30];
            if (i >= 29) {
                double avg = window / 30.0;
                sum4 += avg * avg * avg * avg;
                n++;
            }
        }
        return (int) Math.round(Math.pow(sum4 / n, 0.25));
    }
}
