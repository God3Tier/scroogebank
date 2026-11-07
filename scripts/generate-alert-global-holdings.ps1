param(
    [string]$OutputPath = "$PSScriptRoot/../services/event-alert-service/src/main/resources/fixtures/holdings-snapshot.v1.json"
)

$ErrorActionPreference = 'Stop'
$culture = [Globalization.CultureInfo]::InvariantCulture
$asOf = '2026-10-29T00:00:00Z'
$valuedAt = '2026-10-28T23:00:00Z'

$instruments = @{
    'SGX-D05' = @{ Currency = 'SGD'; Fx = [decimal]1 }
    'SGX-O39' = @{ Currency = 'SGD'; Fx = [decimal]1 }
    'SGX-Z74' = @{ Currency = 'SGD'; Fx = [decimal]1 }
    'SGX-C6L' = @{ Currency = 'SGD'; Fx = [decimal]1 }
    'NASDAQ-AAPL' = @{ Currency = 'USD'; Fx = [decimal]1.33 }
    'NASDAQ-MSFT' = @{ Currency = 'USD'; Fx = [decimal]1.33 }
    'NASDAQ-NVDA' = @{ Currency = 'USD'; Fx = [decimal]1.33 }
    'NASDAQ-GOOGL' = @{ Currency = 'USD'; Fx = [decimal]1.33 }
    'NYSE-JPM' = @{ Currency = 'USD'; Fx = [decimal]1.33 }
    'NYSE-V' = @{ Currency = 'USD'; Fx = [decimal]1.33 }
    'NYSE-KO' = @{ Currency = 'USD'; Fx = [decimal]1.33 }
    'NYSE-DIS' = @{ Currency = 'USD'; Fx = [decimal]1.33 }
    'LSE-HSBA' = @{ Currency = 'GBP'; Fx = [decimal]1.72 }
    'LSE-SHEL' = @{ Currency = 'GBP'; Fx = [decimal]1.72 }
    'HKEX-0700' = @{ Currency = 'HKD'; Fx = [decimal]0.17 }
    'HKEX-9988' = @{ Currency = 'HKD'; Fx = [decimal]0.17 }
}

$holders = [ordered]@{
    'SGX-D05' = 1..14
    'NASDAQ-AAPL' = @(5,6,7,8,9,10,12,13,14,15,16,2)
    'NASDAQ-MSFT' = @(9,10,11,13,14,15,16,17,18,3)
    'SGX-O39' = @(13,14,16,17,18,19,20,1,4)
    'NYSE-JPM' = @(17,19,20,1,2,3,4,5)
    'HKEX-0700' = 3..9
    'SGX-Z74' = 8..13
    'LSE-SHEL' = 14..18
    'SGX-C6L' = @(19,20,1,2)
    'NYSE-V' = 6..9
    'NASDAQ-NVDA' = 10..12
    'LSE-HSBA' = 15..16
    'NASDAQ-GOOGL' = 18..20
    'NYSE-KO' = 2..4
    'NYSE-DIS' = 11..12
    'HKEX-9988' = @(7)
}

$byClient = @{}
1..20 | ForEach-Object { $byClient[$_] = [Collections.Generic.List[string]]::new() }
foreach ($instrumentId in $holders.Keys) {
    foreach ($client in $holders[$instrumentId]) { $byClient[$client].Add($instrumentId) }
}

if (($byClient.Values | Where-Object { $_.Count -lt 4 -or $_.Count -gt 6 }).Count -ne 0) {
    throw 'Every synthetic client must hold four to six real instruments.'
}

function DecimalText([decimal]$value, [int]$scale) {
    return $value.ToString('F' + $scale, $culture)
}

$positions = [Collections.Generic.List[object]]::new()
$positions.Add([ordered]@{
    snapshotId = '30000000-0000-4000-8000-000000000001'; clientRef = 'syn-client-001'
    accountRef = 'syn-account-001'; assignedAgentSubject = 'syn-agent-001'; instrumentId = 'SYN-SGX-001'
    quantity = '100.00000000'; unitPrice = '25.00000000'; valuationCurrency = 'SGD'; fxToSgd = '1.00000000'
    valueSgd = '2500.0000'; valuedAt = '2026-09-26T23:00:00Z'; freshness = 'FRESH'; sourceVersion = 'synthetic-holdings-v1'
})
$positions.Add([ordered]@{
    snapshotId = '30000000-0000-4000-8000-000000000002'; clientRef = 'syn-client-002'
    accountRef = 'syn-account-002'; assignedAgentSubject = 'syn-agent-002'; instrumentId = 'SYN-SGX-001'
    quantity = '40.00000000'; unitPrice = '25.00000000'; valuationCurrency = 'SGD'; fxToSgd = '1.00000000'
    valueSgd = '1000.0000'; valuedAt = '2026-09-25T00:00:00Z'; freshness = 'STALE'; sourceVersion = 'synthetic-holdings-v1'
})

$sequence = 101
foreach ($client in 1..20) {
    $ids = @($byClient[$client])
    [decimal]$available = 100000
    if ($client -eq 1) { $available -= 2500 }
    if ($client -eq 2) { $available -= 1000 }
    $values = @{}
    if ($client -eq 10) {
        $values['NASDAQ-NVDA'] = [decimal]55000
    } elseif ($client -eq 15) {
        $values['LSE-HSBA'] = [decimal]50000
    }
    $allocated = ($values.Values | Measure-Object -Sum).Sum
    if ($null -eq $allocated) { $allocated = 0 }
    [decimal]$remaining = $available - $allocated
    $ordinary = @($ids | Where-Object { -not $values.ContainsKey($_) })
    [decimal]$share = [Math]::Round($remaining / $ordinary.Count, 4)
    for ($index = 0; $index -lt $ordinary.Count; $index++) {
        $id = $ordinary[$index]
        $values[$id] = if ($index -eq $ordinary.Count - 1) {
            $remaining - ($share * ($ordinary.Count - 1))
        } else { $share }
    }
    foreach ($id in $ids) {
        $instrument = $instruments[$id]
        [decimal]$value = $values[$id]
        [decimal]$quantity = [Math]::Round($value / ([decimal]100 * $instrument.Fx), 8)
        $positions.Add([ordered]@{
            snapshotId = '31000000-0000-4000-8000-' + $sequence.ToString('D12')
            clientRef = 'syn-client-' + $client.ToString('D3')
            accountRef = 'syn-account-' + $client.ToString('D3')
            assignedAgentSubject = 'syn-agent-' + ([int](($client - 1) % 4 + 1)).ToString('D3')
            instrumentId = $id
            quantity = DecimalText $quantity 8
            unitPrice = '100.00000000'
            valuationCurrency = $instrument.Currency
            fxToSgd = DecimalText $instrument.Fx 8
            valueSgd = DecimalText $value 4
            valuedAt = $valuedAt
            freshness = 'FRESH'
            sourceVersion = 'global-synthetic-holdings-v1'
        })
        $sequence++
    }
}

$fixture = [ordered]@{ contractVersion = 1; asOf = $asOf; positions = $positions; unavailable = @() }
$json = $fixture | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText((Resolve-Path (Split-Path $OutputPath)).Path + '/' + (Split-Path $OutputPath -Leaf),
    $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
