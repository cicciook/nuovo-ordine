"""Build a hashed modpack and optionally publish it using an authenticated gh CLI."""
import argparse
import base64
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from launcher.updater import ROOTS, digest, validate_manifest
from launcher.config import atomic_json


def gh(*args, input_data=None, check=True):
    result = subprocess.run(["gh", *args], input=input_data, capture_output=True, text=True)
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip() or "GitHub CLI: operazione non riuscita")
    return result


def build_pack(repo, version, source, settings, output):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Usa --repo nomeutente/nuovo-ordine")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", version):
        raise ValueError("Versione non valida, usa per esempio 1.0.0")
    output.mkdir(parents=True,exist_ok=True)
    assets = output / "assets"
    assets.mkdir(exist_ok=True)
    tag = "pack-" + version
    manifest = {"schema":1, "version":version,"minecraft":settings["minecraft"],"forge":settings["forge"],
                "server":settings.get("server",""),"news":settings.get("news",""),"files":[]}
    preserve = set(settings.get("preserve",[]))
    bundle_path = assets / "extras.zip"
    with zipfile.ZipFile(bundle_path,"w",zipfile.ZIP_DEFLATED) as bundle:
      for directory in sorted(ROOTS):
        base = source / directory
        if not base.exists():
            continue
        for file in sorted(base.rglob("*")):
            if file.is_symlink():
                raise ValueError(f"Non distribuire collegamenti simbolici: {file}")
            if not file.is_file() or file.name in (".gitkeep",".DS_Store"):
                continue
            name = file.relative_to(source).as_posix()
            sha = digest(file)
            asset = sha + ".bin"
            entry = {"path":name,"size":file.stat().st_size,"sha256":sha,
                     "mode":"preserve" if name in preserve else "replace"}
            if directory == "mods":
                shutil.copyfile(file, assets / asset)
                entry["url"] = f"https://github.com/{repo}/releases/download/{tag}/{asset}"
            else:
                bundle.write(file,name)
                entry["archive"] = "extras"
            manifest["files"].append(entry)
    if any(item.get("archive") == "extras" for item in manifest["files"]):
        manifest["archives"] = {"extras":{"size":bundle_path.stat().st_size,"sha256":digest(bundle_path),
            "url":f"https://github.com/{repo}/releases/download/{tag}/extras.zip"}}
    else:
        bundle_path.unlink()
    manifest["files"].extend(settings.get("external_files",[]))
    if not any(f["path"].startswith("mods/") for f in manifest["files"]):
        raise ValueError("Aggiungi le mod CLIENT in modpack/mods prima di pubblicare. Non pubblico un pack vuoto.")
    validate_manifest(manifest)
    atomic_json(output / "pack.json",manifest)
    return manifest


def publish(repo, branch, version, output):
    info = json.loads(gh("repo","view",repo,"--json","isPrivate").stdout)
    if info["isPrivate"]:
        raise ValueError("Questo launcher richiede un repository pubblico.")
    endpoint = f"repos/{repo}/contents/pack.json"
    # Verify the target branch and read its existing manifest before creating a release.
    gh("api",f"repos/{repo}/branches/{branch}")
    prior = gh("api",endpoint + "?ref=" + branch,check=False)
    sha = None
    if prior.returncode == 0:
        sha = json.loads(prior.stdout)["sha"]
    elif "404" not in prior.stderr:
        raise RuntimeError("Impossibile leggere pack.json: " + prior.stderr)
    tag = "pack-" + version
    gh("release","create",tag,"--repo",repo,"--target",branch,"--draft","--title","Modpack " + version,
       "--notes","Modpack Nuovo Ordine " + version)
    files = list((output / "assets").iterdir()) + [output / "pack.json"]
    for start in range(0,len(files),30):
        gh("release","upload",tag,"--repo",repo,*map(str,files[start:start+30]))
    gh("release","edit",tag,"--repo",repo,"--draft=false","--latest=false")
    payload = {"message":"Aggiorna modpack a " + version,"branch":branch,
               "content":base64.b64encode((output / "pack.json").read_bytes()).decode()}
    if sha:
        payload["sha"] = sha
    gh("api", "--method","PUT",endpoint,"--input","-",input_data=json.dumps(payload))
    print("Pubblicato: i launcher scaricheranno il pack al prossimo avvio o prima di Gioca.")


def main():
    parser = argparse.ArgumentParser(description="Pubblica il modpack Nuovo Ordine")
    parser.add_argument("--repo",required=True)
    parser.add_argument("--version",required=True)
    parser.add_argument("--branch",default="main")
    parser.add_argument("--source",type=Path,default=ROOT / "modpack")
    parser.add_argument("--settings",type=Path,default=ROOT / "pack-settings.json")
    parser.add_argument("--publish",action="store_true",help="Pubblica su GitHub usando gh auth login")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+",args.branch):
        parser.error("Usa un nome semplice per il ramo, per esempio main")
    output = ROOT / "release-pack" / args.version
    if output.exists():
        parser.error("La cartella di output esiste: scegli una versione nuova o elimina manualmente la precedente.")
    try:
        settings = json.loads(args.settings.read_text("utf-8"))
        manifest = build_pack(args.repo,args.version,args.source,settings,output)
        print(f'Preparati {len(manifest["files"])} file in {output}')
        if args.publish:
            publish(args.repo,args.branch,args.version,output)
        else:
            print("Nessuna pubblicazione eseguita. Carica gli assets in una release pack-" + args.version +
                  " e poi copia pack.json nella radice del ramo " + args.branch + ".")
    except Exception as exc:
        print("Errore: " + str(exc),file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
