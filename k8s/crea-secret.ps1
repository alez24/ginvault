# =============================================================================
# Crea (o aggiorna) i Secret di GinVault nel namespace "ginvault".
#
#   Uso, dalla cartella del progetto:   .\k8s\crea-secret.ps1
#
# I valori non stanno in nessun file del repository:
#   - gateway-segreti  client id/secret OAuth ed elenchi RUOLI_*, letti dal .env
#   - pagamenti-stripe la sola chiave segreta di Stripe (STRIPE_SECRET_KEY),
#                      letta dal .env: il pod di pagamenti non vede i segreti
#                      OAuth. Lo script dice se la chiave c'e' ed e' di test,
#                      senza stamparla
#   - edizioni-db,     utente e password di Postgres, uno per database. La
#     ricette-db,      password viene generata a caso la PRIMA volta e poi non
#     magazzino-db,    viene piu' toccata: il database, una volta inizializzato
#     produzione-db,   sul volume, ricorda la sua password. Per ripartire da zero
#     pagamenti-db,    vanno cancellati insieme il Secret e il volume (PVC).
#     notifiche-db
#
# Si puo' rilanciare dopo aver cambiato il .env: gateway-segreti e
# pagamenti-stripe vengono aggiornati. I pod leggono le variabili d'ambiente
# solo all'avvio, quindi poi vanno riavviati:
#   kubectl rollout restart deployment/gateway -n ginvault
#   kubectl rollout restart deployment/pagamenti -n ginvault
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

# --- 3) pagamenti-stripe, dalla riga STRIPE_SECRET_KEY del .env ---------------
# Si legge come fa Docker Compose: spazi e virgolette intorno al valore non
# contano. Se la riga manca o e' vuota il Secret si crea lo stesso, con la
# chiave vuota: pagamenti parte e lo segnala nel log.
$riga = Get-Content $envFile | Where-Object { $_ -match '^\s*STRIPE_SECRET_KEY\s*=' } | Select-Object -Last 1
$chiave = ''
if ($riga) {
    $chiave = ($riga -split '=', 2)[1].Trim()
    if ($chiave -match '^"(.*)"$' -or $chiave -match "^'(.*)'$") {
        $chiave = $Matches[1]
    } else {
        $chiave = ($chiave -replace '\s+#.*$', '').Trim()   # commento in fondo alla riga
    }
}
kubectl create secret generic pagamenti-stripe --namespace $ns --from-literal=STRIPE_SECRET_KEY=$chiave --dry-run=client -o yaml |
    kubectl apply --server-side --field-manager crea-secret -f -
if ($LASTEXITCODE -ne 0) { throw 'Creazione di pagamenti-stripe fallita' }
if (-not $riga) {
    Write-Warning 'Nel .env non c''e'' la riga STRIPE_SECRET_KEY: gli addebiti non passeranno (pagamenti.dlt)'
} elseif ($chiave -eq '') {
    Write-Warning 'STRIPE_SECRET_KEY nel .env e'' vuota: gli addebiti non passeranno (pagamenti.dlt)'
} elseif ($chiave -notmatch '^(sk|rk)_test_') {
    Write-Warning 'STRIPE_SECRET_KEY non comincia con sk_test_: pagamenti usa solo chiavi di test (una sk_live_ ne blocca l''avvio)'
} else {
    Write-Host 'secret/pagamenti-stripe: chiave di test presente'
}
Remove-Variable chiave, riga

# --- 4) Un Secret per database, solo se non esiste ancora --------------------
foreach ($servizio in @('edizioni', 'ricette', 'magazzino', 'produzione', 'pagamenti', 'notifiche')) {
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
