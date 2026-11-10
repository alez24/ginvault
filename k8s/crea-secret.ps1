# =============================================================================
# Crea (o aggiorna) i Secret di GinVault nel namespace "ginvault".
#
#   Uso, dalla cartella del progetto:   .\k8s\crea-secret.ps1
#
# I valori non stanno in nessun file del repository:
#   - gateway-segreti  client id/secret OAuth ed elenchi RUOLI_*, letti dal .env
#   - edizioni-db,     utente e password di Postgres, uno per database. La
#     ricette-db,      password viene generata a caso la PRIMA volta e poi non
#     magazzino-db,    viene piu' toccata: il database, una volta inizializzato
#     produzione-db    sul volume, ricorda la sua password. Per ripartire da zero
#                      vanno cancellati insieme il Secret e il volume (PVC).
#
# Si puo' rilanciare dopo aver cambiato il .env: gateway-segreti viene
# aggiornato. I pod leggono le variabili d'ambiente solo all'avvio, quindi poi
# va riavviato il gateway:   kubectl rollout restart deployment/gateway -n ginvault
#
# Nota: un Secret NON e' cifrato, i valori sono solo in base64. Lo protegge il
# controllo degli accessi (RBAC): chi puo' leggere i Secret del namespace puo'
# leggerne i valori.
# =============================================================================

$ns = 'ginvault'
$envFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'

if (-not (Test-Path $envFile)) {
    throw "Manca $envFile : copia .env.example in .env e compilalo"
}

# --- 1) Namespace (idempotente: se esiste gia' non cambia nulla) -------------
kubectl apply -f (Join-Path $PSScriptRoot 'namespace.yaml')
if ($LASTEXITCODE -ne 0) { throw 'Creazione del namespace fallita' }

# --- 2) gateway-segreti, dal .env --------------------------------------------
# "create ... --dry-run=client -o yaml" costruisce il Secret in locale SENZA
# inviarlo al cluster; "apply" lo crea se manca o lo aggiorna se esiste.
# --server-side: l'apply classico salverebbe una copia dell'oggetto (valori
# compresi) nell'annotazione last-applied-configuration, che "kubectl describe"
# mostra. Con il server-side apply questa copia non esiste.
kubectl create secret generic gateway-segreti --namespace $ns --from-env-file $envFile --dry-run=client -o yaml |
    kubectl apply --server-side --field-manager crea-secret -f -
if ($LASTEXITCODE -ne 0) { throw 'Creazione di gateway-segreti fallita' }

# --- 3) Un Secret per database, solo se non esiste ancora --------------------
foreach ($servizio in @('edizioni', 'ricette', 'magazzino', 'produzione')) {
    $nome = "$servizio-db"
    $esistente = kubectl get secret $nome --namespace $ns --ignore-not-found -o name
    if ($esistente) {
        Write-Host "secret/$nome esiste gia: password invariata"
    } else {
        # 24 byte casuali da un generatore crittografico -> base64 senza + / =
        $byte = New-Object byte[] 24
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($byte)
        $password = [Convert]::ToBase64String($byte) -replace '[+/=]', ''
        kubectl create secret generic $nome --namespace $ns `
            --from-literal=POSTGRES_USER=$servizio `
            --from-literal=POSTGRES_PASSWORD=$password
        if ($LASTEXITCODE -ne 0) { throw "Creazione di $nome fallita" }
    }
}

kubectl get secrets --namespace $ns
