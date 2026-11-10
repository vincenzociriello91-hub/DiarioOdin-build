# DiarioOdin-build

Solo per compilare l'app **Diario Odin** (Actions gratuite). Il codice vero vive nel repository privato Odin2PortalTweaks.
L'APK compilato finisce in `modulo/app/DiarioOdin.apk`.

## Kernel della Odin 2 Portal

La cartella `kernel/` compila su GitHub Actions il kernel di LineageOS della Odin 2 Portal: una copia identica all'originale (riferimento) e una su misura (`futex_waitv`, BBR). Lo zip viene pubblicato tra le Release solo se i simboli esportati restano compatibili con i moduli della ROM. Parametri in `kernel/versione.conf`, esiti di ogni compilazione in `kernel/esiti/`.
