from launcher import selfupdate, ui, ui_tweaks, updater_v2

# Usa il nuovo updater robusto senza modificare il resto della UI.
selfupdate.apply_update = updater_v2.apply_update
ui_tweaks.apply(ui)

if __name__ == "__main__":
    ui.main()
