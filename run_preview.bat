@echo off
title LART BMS Mobile Preview Server
echo ========================================================
echo       LART BMS Mobile Preview (SolidWorks 3D CAD)
echo ========================================================
echo A iniciar servidor local para visualizacao 3D WebGL...
echo URL: http://localhost:8085/mobile_preview.html
echo.
echo Para fechar o servidor, fecha esta janela ou pressiona Ctrl+C.
echo.
start http://localhost:8085/mobile_preview.html

powershell -NoProfile -ExecutionPolicy Bypass -Command "$l = New-Object System.Net.HttpListener; $l.Prefixes.Add('http://localhost:8085/'); $l.Start(); Write-Host 'Servidor ativo em http://localhost:8085/'; while ($l.IsListening) { try { $ctx = $l.GetContext(); $sub = $ctx.Request.Url.LocalPath.TrimStart('/'); if ([string]::IsNullOrEmpty($sub)) { $sub = 'mobile_preview.html' }; $p = Join-Path (Get-Location) $sub; if (Test-Path $p -PathType Leaf) { $b = [System.IO.File]::ReadAllBytes($p); $ext = [System.IO.Path]::GetExtension($p).ToLower(); $m = switch ($ext) { '.html' {'text/html; charset=utf-8'} '.js' {'application/javascript'} '.css' {'text/css'} '.glb' {'model/gltf-binary'} '.png' {'image/png'} '.jpg' {'image/jpeg'} default {'application/octet-stream'} }; $ctx.Response.ContentType = $m; $ctx.Response.ContentLength64 = $b.Length; $ctx.Response.OutputStream.Write($b, 0, $b.Length) } else { $ctx.Response.StatusCode = 404 }; $ctx.Response.OutputStream.Close() } catch {} }"
pause
