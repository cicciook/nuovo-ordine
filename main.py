from launcher import hub_tweaks, ui, ui_tweaks

ui_tweaks.apply(ui)
hub_tweaks.apply(ui)

if __name__ == "__main__":
    ui.main()
