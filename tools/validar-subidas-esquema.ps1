param([string]$PgBin = 'C:/Program Files/PostgreSQL/18/bin')
$ErrorActionPreference = 'Stop'
$d45Workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$d45Output = Join-Path $d45Workspace 'target/subidas-d45'
$d45Schema = 'd45_migration_' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$d45Data = [System.IO.Path]::GetFullPath((Join-Path $d45Output 'postgres/data')).Replace('\','/')
$d45Psql = Join-Path $PgBin 'psql.exe'
$d45Args = @('-X','-w','-h','127.0.0.1','-p','55445','-U','subidas_d45')
$d45Actual = (& $d45Psql @d45Args '-d' 'postgres' '-A' '-t' '-c' 'show data_directory;').Trim().Replace('\','/')
if ($LASTEXITCODE -ne 0 -or $d45Actual -ne $d45Data) { throw 'Not the private workspace PostgreSQL cluster' }
$d45DbExists = & $d45Psql @d45Args '-d' 'postgres' '-A' '-t' '-c' "select 1 from pg_database where datname='subidas_d45_migrations';"
if ($d45DbExists -ne '1') {
    & (Join-Path $PgBin 'createdb.exe') '-w' '-h' '127.0.0.1' '-p' '55445' '-U' 'subidas_d45' 'subidas_d45_migrations'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create isolated migration database' }
}
$d45Script = @'
SET client_min_messages TO WARNING;
CREATE SCHEMA IF NOT EXISTS d45_migration;
SET search_path TO d45_migration;
CREATE TABLE IF NOT EXISTS media_assets (
  id uuid PRIMARY KEY, tenant_id varchar(255), client_upload_id varchar(64),
  status varchar(30), size_bytes bigint,
  CONSTRAINT ux_media_client_upload UNIQUE(tenant_id, client_upload_id)
);
INSERT INTO media_assets(id,tenant_id,client_upload_id,status,size_bytes)
VALUES('00000000-0000-0000-0000-000000000045','d45-legacy','legacy-client-d45','PENDING',123)
ON CONFLICT(id) DO NOTHING;
DO $fresh$
BEGIN
  IF (SELECT count(*) FROM information_schema.columns WHERE table_schema='d45_migration'
      AND table_name='media_assets') <> 5 THEN RAISE EXCEPTION 'Fixture must start from legacy schema'; END IF;
END $fresh$;
'@
foreach ($d45Round in 1..2) {
    foreach ($d45Proposal in @('subidas-d43-coordinacion.sql','subidas-d44-apertura.sql')) {
        $d45File = (Join-Path $d45Workspace ('docs/sql/' + $d45Proposal)).Replace('\','/').Replace("'","''")
        $d45Script += "`n\i '$d45File'`n"
    }
}
$d45Script += @'
DO $d45$
DECLARE n integer;
BEGIN
  SELECT count(*) INTO n FROM information_schema.columns
  WHERE table_schema='d45_migration' AND table_name='media_assets'
    AND column_name IN ('upload_decision','upload_lease_owner','upload_lease_until',
      'upload_expires_at','upload_protected_until','upload_next_attempt_at','upload_attempts',
      'upload_last_error','upload_owner_id','upload_identity_hash','upload_declared_bytes',
      'upload_protocol_version','upload_opening_state');
  IF n<>13 THEN RAISE EXCEPTION 'Expected 13 added columns'; END IF;
  IF to_regclass('d45_migration.ix_media_upload_retry') IS NULL
    OR to_regclass('d45_migration.upload_scopes') IS NULL THEN RAISE EXCEPTION 'Missing index or scope table'; END IF;
  IF NOT EXISTS(SELECT 1 FROM media_assets WHERE client_upload_id='legacy-client-d45'
       AND size_bytes=123 AND status='PENDING' AND upload_owner_id IS NULL
       AND upload_protocol_version IS NULL AND upload_opening_state IS NULL) THEN
    RAISE EXCEPTION 'Legacy identity or contents changed';
  END IF;
  BEGIN
    INSERT INTO media_assets(id,tenant_id,client_upload_id,status,size_bytes)
      VALUES('00000000-0000-0000-0000-000000000046','d45-legacy','legacy-client-d45','PENDING',123);
    RAISE EXCEPTION 'Missing uniqueness guarantee';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
END $d45$;
SELECT 'D45_SCHEMA_VERIFIED: two applications, 13 columns, scope table, index, legacy row and uniqueness preserved';
'@
New-Item -ItemType Directory -Path $d45Output -Force | Out-Null
$d45Input = Join-Path $d45Output 'migration-input.sql'
$d45Script = $d45Script.Replace('d45_migration', $d45Schema)
$d45Script | Set-Content -LiteralPath $d45Input -Encoding UTF8
& $d45Psql @d45Args '-d' 'subidas_d45_migrations' '-v' 'ON_ERROR_STOP=1' '-f' $d45Input *> (Join-Path $d45Output 'migration.log')
if ($LASTEXITCODE -ne 0) { throw 'Schema verification failed; see target/subidas-d45/migration.log' }
Write-Output 'Private PostgreSQL schema verification passed; production unchanged'
