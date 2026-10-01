from launcher import hub_tweaks


def test_hub_url_prefers_explicit_setting():
    assert hub_tweaks._safe_hub_url({"server": "mc.example:25565", "hub_url": "https://hub.example/api/all"}) == "https://hub.example/api/all"


def test_hub_url_derives_host_from_minecraft_server():
    assert hub_tweaks._safe_hub_url({"server": "mc.example:25565"}) == "http://mc.example:8765/api/all"
    assert hub_tweaks._safe_hub_url({"server": "[2001:db8::10]:25565"}) == "http://2001:db8::10:8765/api/all"


def test_live_views_render_real_payload():
    payload = {
        "timestamp": 0,
        "online": 7,
        "convoy": "ENROUTE",
        "airdrop": "Aeroporto",
        "moneySunk": 12500,
        "event": {"point": "raffineria", "name": "Raffineria"},
        "influence": [{"name": "Alpha", "score": 150, "legacy": 2}],
        "players": [{"name": "Tester", "kills": 5, "contracts": 3, "loot": 4, "mtsKm": 12.5}],
        "openContracts": 6,
        "used": [{"id": "abcd1234", "vehicle": "F40", "plate": "NO-AA-123", "price": 25000, "seller": "Tester"}],
    }
    assert "7" in hub_tweaks._server_text(payload)
    assert "Raffineria" in hub_tweaks._server_text(payload)
    assert "Alpha" in hub_tweaks._ranking_text(payload)
    assert "★2" in hub_tweaks._ranking_text(payload)
    assert "F40" in hub_tweaks._market_text(payload)
    assert "6" in hub_tweaks._market_text(payload)
