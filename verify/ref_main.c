/*
 * 差分测试驱动：用参考固件自带的 xds_protocol.c 解析 cases.txt 里的负载，
 * 与 Android 端 Java 实现 XdsParser.java 的输出逐字段比对。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "xds_protocol.h"

int main(int argc, char ** argv)
{
    const char * path = (argc > 1) ? argv[1] : "cases.txt";
    FILE * fp = fopen(path, "r");
    if (fp == NULL)
    {
        fprintf(stderr, "cannot open %s\n", path);
        return 1;
    }

    char line[512];
    while (fgets(line, sizeof(line), fp) != NULL)
    {
        unsigned char payload[256];
        size_t length = 0;

        char * tok = strtok(line, " \t\r\n");
        while ((tok != NULL) && (length < sizeof(payload)))
        {
            payload[length++] = (unsigned char) strtoul(tok, NULL, 16);
            tok = strtok(NULL, " \t\r\n");
        }

        xds_power_measurement_t m;
        memset(&m, 0, sizeof(m));   /* 每次都用全新结构体，与 Java 侧一致 */
        bool ok = xds_power_measurement_parse(payload, length, &m);

        printf("len=%u ok=%d total=%u left=%d right=%d cadence=%u angle=%d err=%u\n",
               (unsigned) length,
               ok ? 1 : 0,
               (unsigned) m.total_power_w,
               (int) m.left_power_w,
               (int) m.right_power_w,
               (unsigned) m.cadence_rpm,
               (int) m.angle_deg,
               (unsigned) m.error_code);
    }

    fclose(fp);
    return 0;
}
