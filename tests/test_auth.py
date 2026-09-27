import threading
from urllib.parse import urlsplit,parse_qs
from urllib.request import urlopen
from urllib.error import HTTPError
from launcher import auth


def test_login_rejects_wrong_state_and_uses_pkce(monkeypatch):
    received={}
    threads=[]
    def browser(url):
        params=parse_qs(urlsplit(url).query)
        assert params["code_challenge_method"]==["S256"]
        assert "code_challenge" in params
        def callback():
            try:
                urlopen("http://127.0.0.1:53682/callback?state=wrong&code=bad",timeout=5)
            except HTTPError as exc:
                assert exc.code==400
            with urlopen("http://127.0.0.1:53682/callback?state="+params["state"][0]+"&code=good",timeout=5) as r:
                assert r.status==200
        thread=threading.Thread(target=callback)
        thread.start()
        threads.append(thread)
    def complete(client,secret,redirect,code,verifier):
        received.update(code=code,verifier=verifier)
        assert secret is None
        return {"name":"Player","id":"uuid","access_token":"fake","refresh_token":"fake"}
    monkeypatch.setattr(auth.msa,"complete_login",complete)
    monkeypatch.setattr(auth,"remember",lambda *args:None)
    assert auth.login("test-client",lambda s:None,browser)["name"]=="Player"
    for thread in threads:thread.join(5)
    assert received["code"]=="good"
    assert len(received["verifier"])>=43
