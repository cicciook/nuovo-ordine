# Componenti di terze parti

Il codice specifico Nuovo Ordine è fornito nel pacchetto sorgente. Le dipendenze
mantengono le rispettive licenze e i propri avvisi; non vengono rilicenziate dal progetto.

- PySide6 / Qt: LGPLv3, GPLv3 o licenza commerciale secondo componente e distribuzione.
  https://doc.qt.io/qtforpython-6/licenses.html
- minecraft-launcher-lib: BSD-2-Clause.
  https://github.com/JakobDev/minecraft-launcher-lib
- requests: Apache-2.0. https://requests.readthedocs.io/
- keyring: MIT. https://github.com/jaraco/keyring
- platformdirs: MIT. https://github.com/tox-dev/platformdirs
- filelock: Unlicense. https://github.com/tox-dev/filelock
- PyInstaller: GPL con eccezione per la distribuzione delle applicazioni.
  https://pyinstaller.org/en/stable/license.html

Prima di distribuire una build binaria, conserva gli avvisi e le licenze raccolti
dal pacchettizzatore e quelli applicabili delle dipendenze transitive. La build è
in formato cartella, con librerie Qt separate. Il pacchetto sorgente rende disponibile
il codice dell'applicazione e la procedura di build.

Minecraft, Microsoft, Forge e le singole mod mantengono nomi, marchi e licenze dei
rispettivi titolari. Questo progetto non include il gioco né autorizza la
ridistribuzione di mod che non la consentono.
