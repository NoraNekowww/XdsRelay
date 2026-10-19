$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$jdk = 'E:\Alpha_nz_Files\_toolchain\jdk\bin'
$src = 'E:\Alpha_nz_Files\XdsRelay\app\src\main\java\com\nz\xdsrelay'
$ver = 'E:\Alpha_nz_Files\XdsRelay\verify'
$out = "$ver\out"

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Output '=== 编译 FitWriter + 测试驱动 ==='
& "$jdk\javac.exe" -encoding UTF-8 -d $out "$src\FitWriter.java" "$ver\java\com\nz\xdsrelay\FitCheck.java" 2>&1 | Write-Output
if ($LASTEXITCODE -ne 0) { Write-Output 'javac 失败'; exit 1 }

Write-Output '=== 生成测试 FIT ==='
& "$jdk\java.exe" -cp $out com.nz.xdsrelay.FitCheck $out 2>&1 | Write-Output

Write-Output ''
Write-Output '=== 用 fitdecode 独立回读校验 ==='
python "$ver\fit_verify.py" $out 2>&1 | Out-String -Width 200 | Write-Output
Write-Output "PY_EXIT=$LASTEXITCODE"
