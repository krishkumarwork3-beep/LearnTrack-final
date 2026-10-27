#!/usr/bin/env pwsh
# scratch_archive/clear_db.ps1
# One-off script: clears all user data from learntrack.db without touching schema.
# Usage: pwsh -File scratch_archive/clear_db.ps1
# Run from the project root (D:\learntrack-project).

$DbPath = "D:\learntrack-project\learntrack.db"
$sqlite3 = "sqlite3"   # must be on PATH, or set full path here

# Step 1: detect all user tables dynamically
$tables = & $sqlite3 $DbPath "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;"
Write-Host "Tables found: $($tables -join ', ')"

# Step 2: collect row counts before clear
Write-Host "`n=== Row counts BEFORE clear ==="
foreach ($t in $tables) {
    $cnt = & $sqlite3 $DbPath "SELECT COUNT(*) FROM [$t];"
    Write-Host "  $t : $cnt"
}

# Step 3: DELETE all rows in one FK-OFF transaction, reset autoincrement, then VACUUM
$deleteStmts = ""
foreach ($t in $tables) {
    $deleteStmts += "DELETE FROM [$t];`n"
}

$script = @"
PRAGMA foreign_keys = OFF;
BEGIN;
$deleteStmts
DELETE FROM sqlite_sequence;
COMMIT;
PRAGMA foreign_keys = ON;
"@

Write-Host "`nRunning DELETE transaction..."
$script | & $sqlite3 $DbPath
if ($LASTEXITCODE -ne 0) { Write-Error "DELETE failed (exit $LASTEXITCODE). Restore from backup!"; exit 1 }

# Step 4: Re-seed default settings (mirrors schema.sql INSERT OR IGNORE rows + SemesterService extras)
$seedSql = @"
INSERT OR IGNORE INTO settings (key, value) VALUES ('skill_session_length', '1.0');
INSERT OR IGNORE INTO settings (key, value) VALUES ('revision_majority_ratio', '0.8');
INSERT OR IGNORE INTO settings (key, value) VALUES ('pyq_practice_hours', '2');
INSERT OR IGNORE INTO settings (key, value) VALUES ('skill_lookahead_days', '14');
INSERT OR IGNORE INTO settings (key, value) VALUES ('break_minutes', '0');
INSERT OR IGNORE INTO settings (key, value) VALUES ('min_session_minutes', '15');
INSERT OR IGNORE INTO settings (key, value) VALUES ('exam_period_extra_hours_MST', '0.0');
INSERT OR IGNORE INTO settings (key, value) VALUES ('exam_period_extra_hours_EST', '0.0');
"@
Write-Host "Seeding default settings..."
$seedSql | & $sqlite3 $DbPath
if ($LASTEXITCODE -ne 0) { Write-Error "Settings seed failed."; exit 1 }

# Step 5: VACUUM
Write-Host "Running VACUUM..."
& $sqlite3 $DbPath "VACUUM;"
if ($LASTEXITCODE -ne 0) { Write-Error "VACUUM failed."; exit 1 }

# Step 6: Verify row counts after clear
Write-Host "`n=== Row counts AFTER clear (all must be 0, except settings) ==="
foreach ($t in $tables) {
    $cnt = & $sqlite3 $DbPath "SELECT COUNT(*) FROM [$t];"
    Write-Host "  $t : $cnt"
}

# Step 7: Integrity check
Write-Host "`n=== PRAGMA integrity_check ==="
$ic = & $sqlite3 $DbPath "PRAGMA integrity_check;"
Write-Host "  $ic"
if ($ic -ne "ok") { Write-Error "Integrity check FAILED: $ic"; exit 1 }

Write-Host "`nDatabase cleared successfully."
