param(
    [ValidateSet('Snapshot', 'Simple', 'Retouch', 'ContinueRetouch', 'Confirm', 'Status')][string]$Action = 'Snapshot',
    [switch]$PaidAuthorized,
    [switch]$FinalTrialAuthorized,
    [switch]$ExtraConsultAuthorized,
    [string]$BaseUrl = 'http://localhost:8087/api/v1',
    [string]$EnvironmentFile = '../.env',
    [string]$ReportName = '',
    [string]$BearerToken = '',
    [string]$WorkspaceId = 'ed843752-8e99-4b67-8b13-9359ce327b12',
    [string]$SourceId = 'dc20a2a0-d15a-47fe-bd30-0544de01c0ff',
    [string]$ExpectedWorkspaceName = 'Banquetera y Postres',
    [switch]$CreateThreadAuthorized
)
$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$workspaceId = $WorkspaceId
$sourceId = $SourceId
$defaultReportName = if ($FinalTrialAuthorized) { 'assistant-goal-real-final.json' } else { 'assistant-goal-real.json' }
$reportPath = Join-Path $PSScriptRoot ('../target/' + $(if ($ReportName) { $ReportName } else { $defaultReportName }))
if ($ExtraConsultAuthorized -and -not $FinalTrialAuthorized) { throw 'La consulta adicional pertenece exclusivamente a la prueba final.' }
if ($FinalTrialAuthorized -and ($Action -eq 'Simple' -or ($Action -eq 'ContinueRetouch' -and -not $ExtraConsultAuthorized))) { throw 'La prueba final admite solo el retoque aprobado y la consulta adicional expresamente autorizada.' }
if ($Action -in @('Simple','Retouch','ContinueRetouch','Confirm') -and -not $PaidAuthorized) { throw 'Esta acción requiere autorización explícita de consumo.' }

# Credenciales solo en memoria. Este helper está limitado al perfil aprobado.
if ($BearerToken) {
    $headers = @{Authorization='Bearer ' + $BearerToken}
} else {
    $config = @{}
    Get-Content (Join-Path $PSScriptRoot $EnvironmentFile) | ForEach-Object {
        if ($_ -match '^\s*([^#=\s]+)\s*=\s*(.*)$') { $config[$matches[1]] = $matches[2].Trim().Trim('"').Trim("'") }
    }
    if (-not $config['JWT_SECRET']) { throw 'No se encontró la clave configurada. No se usa una clave predeterminada.' }
    function Encode-Url([byte[]]$bytes) { [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_') }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $head = Encode-Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $payload = @{ sub='contacto@rodtech.cloud'; iss='metricol-api'; aud='metricol-app'; iat=$now; exp=$now+1200 } | ConvertTo-Json -Compress
    $unsigned = $head + '.' + (Encode-Url ([Text.Encoding]::UTF8.GetBytes($payload)))
    $hmac = New-Object Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($config['JWT_SECRET'])
    $headers = @{Authorization='Bearer ' + $unsigned + '.' + (Encode-Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($unsigned))))}
    $hmac.Dispose()
}
function Api([string]$Method, [string]$Path, $Body = $null) {
    $parameters = @{Uri=$base+$Path; Method=$Method; Headers=$headers; TimeoutSec=160}
    if ($null -ne $Body) { $parameters.ContentType='application/json; charset=utf-8'; $parameters.Body=[Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress)) }
    try { (Invoke-RestMethod @parameters).result }
    catch { throw ('Falló la consulta local; no se reintentó la operación. Acción: ' + $Action) }
}
function Save-Report { $script:report | ConvertTo-Json -Depth 30 | Set-Content -Encoding UTF8 -LiteralPath $reportPath }
$workspace = Api 'GET' '/workspace'
if ($workspace.id -ne $workspaceId -or $workspace.name -ne $ExpectedWorkspaceName) { throw 'El perfil autenticado no es el negocio autorizado. No se cambió de espacio.' }
$threads = @(Api 'GET' '/assistant/threads')
if ($threads.Count -eq 0 -and $CreateThreadAuthorized) {
    $null = Api 'POST' '/assistant/threads' @{title='Validación controlada de liberación'}
    $threads = @(Api 'GET' '/assistant/threads')
}
if ($threads.Count -ne 1) { throw 'Se requiere una única conversación existente; el helper no crea otra.' }
$threadId = $threads[0].id
$detail = Api 'GET' "/assistant/threads/$threadId"
if (Test-Path -LiteralPath $reportPath) {
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if ($report.workspaceId -ne $workspaceId -or $report.threadId -ne $threadId) { throw 'El informe pertenece a otro contexto.' }
} else {
    $report = [PSCustomObject]@{ workspaceId=$workspaceId; threadId=$threadId; createdAt=[DateTimeOffset]::Now.ToString('o'); turnsReserved=0; generationsReserved=0; simpleKey=$null; retouchKey=$null; simple=$null; retouch=$null; job=$null }
}
if ($Action -in @('Simple','Retouch','ContinueRetouch')) {
    if (@($detail.runs | Where-Object { $_.status -in @('QUEUED','RUNNING','WAITING_CONFIRMATION') }).Count) { throw 'Hay un turno activo o pendiente de confirmación; revísalo antes de enviar otro.' }
    $turnLimit = if ($FinalTrialAuthorized -and $ExtraConsultAuthorized) { 2 } elseif ($FinalTrialAuthorized) { 1 } else { 3 }
    if ($report.turnsReserved -ge $turnLimit) { throw 'Se agotaron las consultas autorizadas de esta prueba.' }
    $field = if ($Action -eq 'Simple') { 'simpleKey' } elseif ($Action -eq 'Retouch') { 'retouchKey' } else { 'continuationKey' }
    if ($Action -eq 'ContinueRetouch' -and -not $report.PSObject.Properties['continuationKey']) { $report | Add-Member -NotePropertyName continuationKey -NotePropertyValue $null }
    if ($report.$field) { throw 'Esta consulta ya fue reservada/enviada. Usa Status; no se repite automáticamente.' }
    $report.$field = 'goal-latency-' + [Guid]::NewGuid().ToString()
    $report.turnsReserved++
    Save-Report
    $request = @{content='¿Cuántas publicaciones tengo programadas? Consulta solo lo necesario y responde brevemente. No cambies nada.';attachmentIds=@();currentPath='/panel/asistente';idempotencyKey=$report.$field}
    if ($Action -in @('Retouch','ContinueRetouch')) {
        $request.content = "Modo de imagen: RETOQUE.`nBrief de la pieza (literal, confirmado por mí):`nRetoca únicamente la foto adjunta para Instagram: una sola versión 4:5, formato post. Mejora iluminación, nitidez y encuadre; conserva exactamente alimentos, ingredientes, porciones, ambiente nocturno y logos existentes. No agregues texto, CTA, personas ni elementos. No publiques, programes ni guardes reglas de marca."
        $request.attachmentIds = @($sourceId)
        if ($FinalTrialAuthorized -and $ExtraConsultAuthorized -and $Action -eq 'ContinueRetouch') {
            $request.content = "Modo de imagen: RETOQUE.`nBrief de la pieza (literal, confirmado por mí):`nQuiero generar UNA NUEVA versión, no recuperar ni reutilizar el retoque que ya existe. Usa únicamente la foto original adjunta para Instagram: una sola versión 4:5, formato post. Mejora iluminación, nitidez y encuadre; conserva alimentos, ingredientes, porciones, ambiente nocturno y logos existentes. Sin texto, CTA, personas ni elementos nuevos. No publiques, programes ni guardes reglas de marca. Prepara la solicitud y muéstrame la confirmación de generación con su coste."
        }
    }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $response = Api 'POST' "/assistant/threads/$threadId/messages" $request
    $watch.Stop()
    $run = @($response.runs | Sort-Object createdAt)[-1]
    $result = [PSCustomObject]@{runId=$run.id; acknowledgedMs=$watch.ElapsedMilliseconds; status=$run.status}
    if ($Action -eq 'Simple') { $report.simple=$result } else {
        if ($FinalTrialAuthorized -and $Action -eq 'ContinueRetouch') { $report | Add-Member -NotePropertyName reusedResult -NotePropertyValue $report.retouch -Force }
        $report.retouch=$result
    }
    Save-Report
    $result | ConvertTo-Json -Compress
    exit
}
if ($Action -eq 'Confirm') {
    if (-not $report.retouch -or $report.generationsReserved -ge 1) { throw 'No hay un retoque pendiente o ya se reservó la única generación autorizada.' }
    $run = @($detail.runs | Where-Object { $_.id -eq $report.retouch.runId })[0]
    $pending = @($detail.toolCalls | Where-Object { $_.runId -eq $run.id -and $_.status -eq 'WAITING_CONFIRMATION' })
    if ($run.status -ne 'WAITING_CONFIRMATION' -or $pending.Count -ne 1 -or $pending[0].toolName -ne 'generar_contenido') { throw 'No coincide la confirmación de generación esperada.' }
    if (-not $detail.pendingConfirmationToken) {
        $detail = Api 'POST' "/assistant/runs/$($run.id)/confirmation-token" @{}
        if (-not $detail.pendingConfirmationToken) { throw 'No fue posible recuperar la confirmación; no se inició ninguna generación.' }
    }
    # Revisar los argumentos persistidos con SQL antes de invocar esta fase: una foto, una red,
    # modo RETOQUE, sin textos/CTA/Chispa. Este helper nunca confirma otras herramientas.
    $report.generationsReserved++
    Save-Report
    $confirmWatch = [Diagnostics.Stopwatch]::StartNew()
    $response = Api 'POST' "/assistant/runs/$($run.id)/confirm" @{token=$detail.pendingConfirmationToken}
    $confirmWatch.Stop()
    $report.retouch | Add-Member -NotePropertyName confirmedAckMs -NotePropertyValue $confirmWatch.ElapsedMilliseconds -Force
    $report.retouch.status = @($response.runs | Where-Object { $_.id -eq $run.id })[0].status
    Save-Report
}
$detail = Api 'GET' "/assistant/threads/$threadId"
if ($Action -eq 'Status' -and $report.retouch -and @($detail.messages | Where-Object {
    $_.runId -eq $report.retouch.runId -and $_.artifactsJson -match '"type"\s*:\s*"generation"'
}).Count) {
    $detail = Api 'GET' "/assistant/runs/$($report.retouch.runId)/generation"
}
$last = @($detail.runs | Sort-Object createdAt)[-1]
$jobId = $null
$receipt = $null
foreach ($message in @($detail.messages | Where-Object { $report.retouch -and $_.runId -eq $report.retouch.runId })) {
    if ($message.artifactsJson) {
        foreach ($artifact in @($message.artifactsJson | ConvertFrom-Json)) { if ($artifact.type -eq 'generation') { $jobId=$artifact.jobId; $receipt=$artifact } }
    }
}
if ($jobId) {
    if ($receipt.status -eq 'READY') {
        # La galería durable es la autoridad tras reiniciar la cola en memoria.
        $report | Add-Member -NotePropertyName recoveredReceipt -NotePropertyValue $receipt -Force
    } else {
        $job = Api 'GET' "/content/jobs/$jobId"
        $report.job = $job
    }
    Save-Report
}
[PSCustomObject]@{runId=$last.id;status=$last.status;error=$last.errorMessage;messages=@($detail.messages | Where-Object {$_.runId -eq $last.id} | Select-Object role,content);receipt=$receipt;job=$report.job;turnsReserved=$report.turnsReserved;generationsReserved=$report.generationsReserved} | ConvertTo-Json -Depth 15
