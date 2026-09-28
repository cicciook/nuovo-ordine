import time

import keyring
import requests
from keyring.backend import get_all_keyring
from minecraft_launcher_lib import microsoft_account as msa
from minecraft_launcher_lib.exceptions import AzureAppNotPermitted, AccountNotOwnMinecraft

SERVICE = "NuovoOrdine.Microsoft"
DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
SCOPE = "XboxLive.SignIn XboxLive.offline_access"


def secure_keyring():
    allowed = (
        "keyring.backends.Windows",
        "keyring.backends.macOS",
        "keyring.backends.SecretService",
        "keyring.backends.kwallet",
    )
    try:
        candidates = [
            b
            for b in get_all_keyring()
            if type(b).__module__.startswith(allowed) and b.priority > 0
        ]
        return max(candidates, key=lambda b: b.priority) if candidates else None
    except Exception:
        return None


def saved_token(client_id):
    if not client_id:
        return None
    backend = secure_keyring()
    try:
        return backend.get_password(SERVICE, client_id) if backend else None
    except Exception:
        return None


def remember(client_id, data, report):
    backend = secure_keyring()
    try:
        if backend:
            backend.set_password(SERVICE, client_id, data["refresh_token"])
            return
    except Exception:
        pass
    report("Portachiavi di sistema non disponibile: accesso valido solo per questa sessione.")


def forget(client_id):
    if not client_id:
        return
    backend = secure_keyring()
    if backend:
        try:
            if backend.get_password(SERVICE, client_id):
                backend.delete_password(SERVICE, client_id)
        except Exception as exc:
            raise RuntimeError("Non riesco a eliminare l'accesso dal portachiavi di sistema.") from exc


def refresh(client_id, token, report):
    data = msa.complete_refresh(client_id, None, None, token)
    remember(client_id, data, report)
    return data


def _oauth_error(data, fallback):
    description = str(data.get("error_description", "")).strip()
    error = str(data.get("error", "")).strip()
    combined = f"{error} {description}"
    if "AADSTS7000218" in combined or error == "unauthorized_client":
        return (
            "L'app Microsoft del launcher non è abilitata come client pubblico. "
            "In Microsoft Entra apri Authentication e imposta Allow public client flows su Yes."
        )
    if "AADSTS700016" in combined or error == "invalid_client":
        return "Il Client ID Microsoft del launcher non è valido o non è disponibile per gli account personali."
    return description or fallback


def _post_oauth(url, data, allow_error_payload=False):
    try:
        response = requests.post(
            url,
            data=data,
            headers={"Accept": "application/json"},
            timeout=(15, 30),
        )
    except requests.RequestException as exc:
        raise RuntimeError("Non riesco a contattare Microsoft. Controlla Internet e riprova.") from exc

    try:
        payload = response.json()
    except ValueError as exc:
        raise RuntimeError("Microsoft ha restituito una risposta non valida. Riprova tra poco.") from exc

    # Nel device-code flow Microsoft usa normalmente HTTP 400 anche per stati
    # non fatali come authorization_pending e slow_down. Durante il polling
    # dobbiamo quindi restituire il JSON al chiamante invece di trasformarlo
    # subito in un errore visibile all'utente.
    if response.status_code >= 400 and not allow_error_payload:
        raise RuntimeError(_oauth_error(payload, f"Microsoft ha rifiutato la richiesta ({response.status_code})."))
    return payload


def _finish_minecraft_login(ms_access_token, refresh_token):
    xbl = msa.authenticate_with_xbl(ms_access_token)
    xbl_token = xbl["Token"]
    userhash = xbl["DisplayClaims"]["xui"][0]["uhs"]

    xsts = msa.authenticate_with_xsts(xbl_token)
    minecraft = msa.authenticate_with_minecraft(userhash, xsts["Token"])
    if "access_token" not in minecraft:
        raise AzureAppNotPermitted()

    access_token = minecraft["access_token"]
    profile = msa.get_profile(access_token)
    if profile.get("error") == "NOT_FOUND":
        raise AccountNotOwnMinecraft()

    profile["access_token"] = access_token
    profile["refresh_token"] = refresh_token
    return profile


def login(client_id, report, open_browser):
    """Microsoft OAuth device-code flow matching Prism Launcher's user experience."""
    if not client_id:
        raise RuntimeError(
            "Il login Microsoft non è configurato in questa build del launcher. "
            "Il proprietario deve configurarlo una sola volta nella build."
        )

    device = _post_oauth(
        DEVICE_CODE_URL,
        {"client_id": client_id, "scope": SCOPE},
    )
    if "device_code" not in device:
        raise RuntimeError(_oauth_error(device, "Microsoft non ha avviato il login."))

    browser_url = device.get("verification_uri_complete") or device.get("verification_uri")
    if not browser_url:
        raise RuntimeError("Microsoft non ha restituito la pagina di accesso.")

    user_code = device.get("user_code", "")
    if user_code:
        report(f"Browser aperto • codice Microsoft: {user_code} • completa l'accesso, il launcher resta in attesa")
    else:
        report("Browser Microsoft aperto. Completa l'accesso: il launcher resta in attesa.")
    open_browser(browser_url)

    interval = max(2, int(device.get("interval", 5)))
    deadline = time.monotonic() + int(device.get("expires_in", 900))
    token = None

    while time.monotonic() < deadline:
        time.sleep(interval)
        data = _post_oauth(
            TOKEN_URL,
            {
                "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                "client_id": client_id,
                "device_code": device["device_code"],
            },
            allow_error_payload=True,
        )
        if "access_token" in data:
            token = data
            break

        error = str(data.get("error", "")).strip()
        if error == "authorization_pending":
            continue
        if error == "slow_down":
            interval += 5
            continue
        if error in ("authorization_declined", "access_denied"):
            raise RuntimeError("Accesso Microsoft annullato.")
        if error == "expired_token":
            raise RuntimeError("Il codice Microsoft è scaduto. Premi di nuovo Accedi con Microsoft.")
        raise RuntimeError(_oauth_error(data, "Accesso Microsoft non riuscito."))

    if not token:
        raise RuntimeError("Accesso Microsoft scaduto. Premi di nuovo Accedi con Microsoft.")

    report("Microsoft autenticato • verifico Xbox e Minecraft…")
    profile = _finish_minecraft_login(token["access_token"], token["refresh_token"])
    remember(client_id, profile, report)
    return profile
