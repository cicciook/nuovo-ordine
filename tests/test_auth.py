from launcher import auth


class FakeResponse:
    def __init__(self, data, status=200):
        self._data = data
        self.status_code = status

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")

    def json(self):
        return self._data


def test_login_uses_prism_style_device_code_flow(monkeypatch):
    calls = []
    opened = []
    reports = []
    remembered = []

    responses = iter([
        FakeResponse({
            "device_code": "device-code",
            "user_code": "ABCD-EFGH",
            "verification_uri": "https://microsoft.com/devicelogin",
            "expires_in": 900,
            "interval": 1,
        }),
        # Microsoft risponde realmente con HTTP 400 finché l'utente non ha
        # ancora completato il device-code flow. Non deve diventare un popup.
        FakeResponse({
            "error": "authorization_pending",
            "error_description": "AADSTS70016: The provided request has not yet been authorized by the user.",
        }, status=400),
        FakeResponse({
            "access_token": "microsoft-access",
            "refresh_token": "microsoft-refresh",
        }),
    ])

    def post(url, data=None, headers=None, timeout=None):
        calls.append((url, dict(data or {})))
        return next(responses)

    monkeypatch.setattr(auth.requests, "post", post)
    monkeypatch.setattr(auth.time, "sleep", lambda _: None)
    monkeypatch.setattr(
        auth,
        "_finish_minecraft_login",
        lambda access, refresh: {
            "name": "Player",
            "id": "uuid",
            "access_token": "minecraft-access",
            "refresh_token": refresh,
        },
    )
    monkeypatch.setattr(auth, "remember", lambda client, data, report: remembered.append((client, data)))

    result = auth.login("test-client", reports.append, opened.append)

    assert result["name"] == "Player"
    assert opened == ["https://microsoft.com/devicelogin"]
    assert calls[0][0] == auth.DEVICE_CODE_URL
    assert calls[0][1]["client_id"] == "test-client"
    assert calls[0][1]["scope"] == "XboxLive.SignIn XboxLive.offline_access"
    assert calls[1][0] == auth.TOKEN_URL
    assert calls[1][1]["grant_type"] == "urn:ietf:params:oauth:grant-type:device_code"
    assert calls[1][1]["device_code"] == "device-code"
    assert remembered[0][0] == "test-client"
    assert any("ABCD-EFGH" in message for message in reports)
    assert any("resta in attesa" in message for message in reports)
