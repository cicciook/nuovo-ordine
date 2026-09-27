import hmac
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit
import keyring
from keyring.backend import get_all_keyring
from minecraft_launcher_lib import microsoft_account as msa

REDIRECT = "http://localhost:53682/callback"
SERVICE = "NuovoOrdine.Microsoft"


def secure_keyring():
    allowed = ("keyring.backends.Windows", "keyring.backends.macOS", "keyring.backends.SecretService", "keyring.backends.kwallet")
    try:
        candidates = [b for b in get_all_keyring() if type(b).__module__.startswith(allowed) and b.priority > 0]
        return max(candidates, key=lambda b: b.priority) if candidates else None
    except Exception:
        return None


def saved_token(client_id):
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
    backend = secure_keyring()
    if backend:
        try:
            if backend.get_password(SERVICE, client_id):
                backend.delete_password(SERVICE, client_id)
        except Exception as exc:
            raise RuntimeError("Non riesco a eliminare l'accesso dal portachiavi di sistema.") from exc


def refresh(client_id, token, report):
    data = msa.complete_refresh(client_id, None, REDIRECT, token)
    remember(client_id, data, report)
    return data


def login(client_id, report, open_browser):
    url, state, verifier = msa.get_secure_login_data(client_id, REDIRECT)
    result = {}

    class Callback(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Authorization codes must never enter the log.

        def do_GET(self):
            parts = urlsplit(self.path)
            query = parse_qs(parts.query)
            valid = parts.path == "/callback" and hmac.compare_digest(query.get("state", [""])[0], state)
            if not valid:
                self.send_error(400, "Invalid callback")
                return
            if "error" in query:
                result["error"] = "Accesso annullato o rifiutato da Microsoft."
            elif "code" in query:
                result["code"] = query["code"][0]
            else:
                self.send_error(400, "Missing authorization code")
                return
            body = b"<html><body><h2>Nuovo Ordine</h2><p>Puoi chiudere questa scheda e tornare al launcher.</p></body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    try:
        server = HTTPServer(("127.0.0.1", 53682), Callback)
    except OSError as exc:
        raise RuntimeError("La porta di login 53682 è occupata. Chiudi gli altri launcher e riprova.") from exc
    with server:
        server.timeout = 1
        open_browser(url)
        report("Completa l'accesso Microsoft nel browser (tempo massimo: 3 minuti).")
        deadline = time.monotonic() + 180
        while not result and time.monotonic() < deadline:
            server.handle_request()
    if "error" in result:
        raise RuntimeError(result["error"])
    if "code" not in result:
        raise RuntimeError("Accesso scaduto. Premi di nuovo Accedi con Microsoft.")
    data = msa.complete_login(client_id, None, REDIRECT, result["code"], verifier)
    remember(client_id, data, report)
    return data
