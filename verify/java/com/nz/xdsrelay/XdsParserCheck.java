package com.nz.xdsrelay;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 差分测试的 Java 侧：读取与 reference C 完全相同的 cases.txt，
 * 用 Android 工程的 XdsParser 解析并打印同样格式的一行。
 */
public class XdsParserCheck {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "cases.txt";
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] toks = line.trim().split("\\s+");
                byte[] payload = new byte[toks.length];
                for (int i = 0; i < toks.length; i++) {
                    payload[i] = (byte) Integer.parseInt(toks[i], 16);
                }

                XdsParser.Measurement m = new XdsParser.Measurement();
                boolean ok = XdsParser.parse(payload, 0, payload.length, m);

                System.out.printf(
                        "len=%d ok=%d total=%d left=%d right=%d cadence=%d angle=%d err=%d%n",
                        payload.length,
                        ok ? 1 : 0,
                        m.totalPowerW,
                        m.leftPowerW,
                        m.rightPowerW,
                        m.cadenceRpm,
                        m.angleDeg,
                        m.errorCode);
            }
        }
    }
}
