# Bridge locale Wacom STU-541

Il bridge usa direttamente il Java SDK Wacom e la connessione TLS della STU-541. Rimane in ascolto esclusivamente su `127.0.0.1:8765`.

## Avvio

1. Eseguire una volta `Compila.cmd` con doppio clic.
2. Avviare `Avvia bridge.cmd` e lasciare aperta la finestra.
3. Aprire `http://127.0.0.1:8765`.

Per autorizzare una web application cloud, prima di avviare impostare l'origine esatta, senza barra finale:

```powershell
$env:WACOM_ALLOWED_ORIGINS='https://app.example.it'
.\start.ps1
```

Le origini `https://www.etweb.cloud` e `https://www.gsdweb.cloud` (anche senza `www`) sono già autorizzate nella configurazione predefinita.

La web application chiama `GET http://127.0.0.1:8765/api/health` e `POST http://127.0.0.1:8765/api/capture`. Il risultato contiene coordinate, pressione, stato della penna e, quando forniti dal dispositivo, contatore temporale e sequenza.

I dati grafometrici sono dati personali delicati: trasferirli e conservarli solo con consenso, protezione adeguata e tempi di conservazione definiti.
