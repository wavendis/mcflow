# Prints one JSON line every 300ms describing what Windows' media controls report as playing.
# pos is the playback position in ms as of "now" (Windows reports it as of LastUpdatedTime).
param([int]$ParentPid)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    $task.Wait(-1) | Out-Null
    $task.Result
}
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]
$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])

while ($true) {
    # quit if Minecraft died without cleaning us up
    if ($ParentPid -and -not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { exit }
    $line = '{}'
    try {
        $s = $mgr.GetCurrentSession()
        if ($s) {
            $props = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
            $tl = $s.GetTimelineProperties()
            $playing = $s.GetPlaybackInfo().PlaybackStatus -eq 'Playing'
            $pos = $tl.Position.TotalMilliseconds
            if ($playing) { $pos += ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalMilliseconds }
            $line = [pscustomobject]@{
                title   = $props.Title
                artist  = $props.Artist
                pos     = [long]$pos
                dur     = [long]$tl.EndTime.TotalMilliseconds
                playing = $playing
            } | ConvertTo-Json -Compress
        }
    } catch {}
    [Console]::Out.WriteLine($line)
    [Console]::Out.Flush()
    Start-Sleep -Milliseconds 300
}
