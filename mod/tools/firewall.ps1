# Opens (or removes) MultiSector's port in Windows Firewall. Must run as admin;
# firewall.cmd next to this file handles that. Also used by "dev firewall".
#
# Port rules apply to every network type, including Public, which is what
# Windows usually assigns to the Hamachi adapter. Clicking "Cancel" on Windows'
# "allow Java?" prompt creates Block rules for the game's java.exe, and Block
# beats Allow, so those are removed too.

param(
    [string]$Action = "open",
    [int]$Port = 7777,
    [string]$Java = ""
)

$ErrorActionPreference = "Stop"
try {
    Get-NetFirewallRule -Group "MultiSector" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    if ($Action -eq "remove") {
        Write-Host "Removed MultiSector firewall rules."
    } else {
        New-NetFirewallRule -Group "MultiSector" -DisplayName "MultiSector (TCP $Port)" -Direction Inbound `
            -Protocol TCP -LocalPort $Port -Action Allow -Profile Any | Out-Null
        New-NetFirewallRule -Group "MultiSector" -DisplayName "MultiSector (UDP $Port)" -Direction Inbound `
            -Protocol UDP -LocalPort $Port -Action Allow -Profile Any | Out-Null
        Write-Host "Opened TCP and UDP port $Port on all network types (Private, Public, Domain)."

        if ($Java -eq "") { $Java = Join-Path $PSScriptRoot "..\..\..\jre\bin\java.exe" }
        if (Test-Path $Java) {
            $Java = (Resolve-Path $Java).Path
            $blocks = Get-NetFirewallApplicationFilter -Program $Java -ErrorAction SilentlyContinue |
                Get-NetFirewallRule | Where-Object { $_.Action -eq "Block" -and $_.Direction -eq "Inbound" }
            foreach ($b in $blocks) {
                Write-Host "Removed a block rule for the game's Java: $($b.DisplayName)"
                $b | Remove-NetFirewallRule
            }
        }
    }
} catch {
    Write-Host "Failed: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ""
    Read-Host "Press Enter to close"
    exit 1
}
Write-Host ""
Read-Host "Press Enter to close"
