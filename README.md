# Wacom Bridge

Bridge Java locale per acquisire firme grafometriche da tavolette Wacom STU e renderle disponibili a una web application tramite API HTTP locali.

## Tavolette supportate

- Wacom STU-541 tramite connessione TLS
- Wacom STU-520A tramite connessione USB

## Avvio rapido su Windows

1. Installare il driver Wacom STU e un JDK Java 17, preferibilmente Eclipse Adoptium.
2. Eseguire `wacom-stu541-java-bridge\Compila.cmd` dopo aver modificato i sorgenti.
3. Eseguire `wacom-stu541-java-bridge\Avvia bridge.cmd`.
4. Aprire `http://127.0.0.1:8765` oppure integrare le API nella propria applicazione.

Il bridge ascolta esclusivamente sull'interfaccia locale `127.0.0.1`.

## API

- `GET /api/health` verifica il bridge e indica il numero di dispositivi TLS e USB rilevati.
- `POST /api/capture` avvia l'acquisizione della firma e restituisce coordinate, pressione e metadati della penna.

La documentazione dettagliata e gli esempi si trovano in [`wacom-stu541-java-bridge`](wacom-stu541-java-bridge/README.md).

## Dipendenze Wacom

Le librerie contenute nella cartella `lib` appartengono ai rispettivi titolari. Prima di ridistribuirle, verificare e rispettare le condizioni di licenza del Wacom STU SDK.

## Sicurezza e privacy

I dati grafometrici sono dati personali delicati. Trasferirli e conservarli soltanto con consenso, protezioni adeguate e tempi di conservazione definiti.
