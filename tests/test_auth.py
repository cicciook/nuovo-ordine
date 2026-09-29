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
    assert calls[0][1]["scope"] == "XboxLive.signin offline_access"
    assert calls[1][0] == auth.TOKEN_URL
    assert calls[1][1]["grant_type"] == "urn:ietf:params:oauth:grant-type:device_code"
    assert calls[1][1]["device_code"] == "device-code"
    assert remembered[0][0] == "test-client"
    assert any("ABCD-EFGH" in message for message in reports)
    assert any("resta in attesa" in message for message in reports)


def test_refresh_uses_same_microsoft_v2_endpoint(monkeypatch):
    calls = []
    remembered = []

    def fake_post(url, data, allow_error_payload=False):
        calls.append((url, dict(data), allow_error_payload))
        return {
            "access_token": "new-ms-access",
            "refresh_token": "rotated-refresh",
        }

    monkeypatch.setattr(auth, "_post_oauth", fake_post)
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

    result = auth.refresh("test-client", "old-refresh", lambda _: None)

    assert calls == [(
        auth.TOKEN_URL,
        {
            "client_id": "test-client",
            "scope": "XboxLive.signin offline_access",
            "refresh_token": "old-refresh",
            "grant_type": "refresh_token",
        },
        True,
    )]
    assert result["refresh_token"] == "rotated-refresh"
    assert remembered[0][0] == "test-client"


def test_session_is_valid_only_before_expiry(monkeypatch):
    monkeypatch.setattr(auth.time, "time", lambda: 1000.0)

    valid = {
        "name": "Player",
        "id": "uuid",
        "access_token": "token",
        "refresh_token": "refresh",
        "expires_at": 1100.0,
    }
    expired = dict(valid, expires_at=999.0)

    assert auth.session_is_valid(valid) is True
    assert auth.session_is_valid(expired) is False
