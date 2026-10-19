$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$tool  = 'E:\Alpha_nz_Files\_toolchain'
$proj  = 'E:\Alpha_nz_Files\XdsRelay'
$ver   = "$proj\verify"
$out   = "$ver\out"
$jdk   = "$tool\jdk"
$gcc   = 'E:\Compiler\mingw64\bin\gcc.exe'

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null
New-Item -ItemType Directory -Force -Path "$ver\ref" | Out-Null

Write-Output '=== 1) 编译参考 C 解析器 (来自 xds_forwarding) ==='
Copy-Item 'E:\Alpha_nz_Files\xds_forwarding_ref\xds_forwarding-main\xds_protocol.c' "$ver\ref\xds_protocol.c" -Force
Copy-Item 'E:\Alpha_nz_Files\xds_forwarding_ref\xds_forwarding-main\xds_protocol.h' "$ver\ref\xds_protocol.h" -Force
& $gcc -O1 -Wall -I "$ver\ref" -o "$out\ref_parser.exe" "$ver\ref_main.c" "$ver\ref\xds_protocol.c" 2>&1 | Write-Output
if (-not (Test-Path "$out\ref_parser.exe")) { Write-Output 'gcc 编译失败'; exit 1 }

Write-Output '=== 2) 编译 Java 侧 (直接复用 Android 工程的 XdsParser / BleCodec) ==='
& "$jdk\bin\javac.exe" -encoding UTF-8 -d $out `
    "$proj\app\src\main\java\com\nz\xdsrelay\XdsParser.java" `
    "$proj\app\src\main\java\com\nz\xdsrelay\BleCodec.java" `
    "$ver\java\com\nz\xdsrelay\XdsParserCheck.java" `
    "$ver\java\com\nz\xdsrelay\BleCodecCheck.java" 2>&1 | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output 'javac 编译失败'; exit 1 }

Write-Output '=== 3) 解析器差分测试: 参考 C  vs  Java ==='
Push-Location $ver
& "$out\ref_parser.exe" cases.txt > "$out\c.txt" 2>&1
& "$jdk\bin\java.exe" -cp $out com.nz.xdsrelay.XdsParserCheck cases.txt > "$out\java.txt" 2>&1
Pop-Location

$cLines = Get-Content "$out\c.txt"
$jLines = Get-Content "$out\java.txt"
$cases  = (Get-Content "$ver\cases.txt" | Where-Object { $_.Trim() -ne '' }).Count

$diff = 0
for ($i = 0; $i -lt $cLines.Count; $i++) {
    $c = $cLines[$i]
    $j = if ($i -lt $jLines.Count) { $jLines[$i] } else { '<missing>' }
    if ($c -ne $j) {
        $diff++
        Write-Output "  MISMATCH line $($i+1):"
        Write-Output "    C   : $c"
        Write-Output "    Java: $j"
    }
}
Write-Output "对比用例数: $cases   行数: C=$($cLines.Count) Java=$($jLines.Count)   不一致: $diff"
if ($diff -eq 0) { Write-Output 'XDS 解析器差分测试: 完全一致 PASS' } else { Write-Output 'XDS 解析器差分测试: FAIL' }

Write-Output '=== 4) 标准蓝牙负载编码自检 ==='
& "$jdk\bin\java.exe" -cp $out com.nz.xdsrelay.BleCodecCheck 2>&1 | Write-Output

Write-Output '=== 5) 前 6 条对照输出 ==='
for ($i = 0; $i -lt [Math]::Min(6, $cLines.Count); $i++) {
    Write-Output ("  [{0}] {1}" -f $cases, $cLines[$i])
}
