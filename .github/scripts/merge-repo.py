import gzip
import html
import sys
import json
from pathlib import Path
import re
import shutil

from google.protobuf import json_format

import index_pb2

REMOTE_REPO: Path = Path.cwd()
LOCAL_REPO: Path = REMOTE_REPO.parent.joinpath(sys.argv[2])

LANGUAGE_REGEX = re.compile(r"aniyomi-([^.]+)")

# Dépôt de publication du fork (à garder cohérent avec APK_BASE_URL de create-repo.py)
PUBLISH_BASE_URL = (
    "https://raw.githubusercontent.com/BilaC0de/extensions-anime/refs/heads/repo-anime"
)

LEGACY_NSFW_TO_WARNING = {
    0: index_pb2.CONTENT_WARNING_SAFE,
    1: index_pb2.CONTENT_WARNING_NSFW,
}


def legacy_to_extension(entry: dict) -> index_pb2.Extension:
    """Convertit une entrée de l'ancien index (format index.min.json) en message Extension."""
    pkg = entry["pkg"]
    version = entry["version"]
    apk_name = entry["apk"].rsplit("/", 1)[-1]
    return index_pb2.Extension(
        name=entry["name"].removeprefix("Aniyomi: "),
        packageName=pkg,
        resources=index_pb2.Resources(
            apkUrl=f"{PUBLISH_BASE_URL}/apk/{apk_name}",
            iconUrl=f"{PUBLISH_BASE_URL}/icon/{pkg}.png",
        ),
        extensionLib=version.split(".")[0],
        versionCode=int(entry["code"]),
        versionName=version,
        contentWarning=LEGACY_NSFW_TO_WARNING.get(
            int(entry.get("nsfw", 0)), index_pb2.CONTENT_WARNING_UNSPECIFIED
        ),
        isTorrent=bool(entry.get("torrent", 0)),
        sources=[
            index_pb2.Source(
                id=int(s["id"]),
                name=s["name"],
                language=s["lang"],
                homeUrl=s["baseUrl"],
            )
            for s in entry.get("sources", [])
        ],
    )


def load_remote_extensions(path: Path) -> list:
    """Retourne les extensions déjà publiées, quel que soit le format du index.json distant."""
    if not path.is_file() or not path.read_text(encoding="utf-8").strip():
        return []

    data = json.loads(path.read_text(encoding="utf-8"))

    if isinstance(data, list):  # ancien format : tableau d'entrées
        print("index.json distant au format legacy : conversion")
        return [legacy_to_extension(e) for e in data]

    if isinstance(data, dict):  # format protobuf-JSON
        return list(
            json_format.ParseDict(data, index_pb2.Index()).extensionList.extensions
        )

    raise ValueError(f"Format inattendu pour {path}: {type(data).__name__}")


to_delete: list[str] = json.loads(sys.argv[1])

for module in to_delete:
    apk_name = f"aniyomi-{module}-v*.*.apk"
    icon_name = f"eu.kanade.tachiyomi.animeextension.{module}.png"
    for file in REMOTE_REPO.joinpath("apk").glob(apk_name):
        print(file.name)
        file.unlink(missing_ok=True)
    for file in REMOTE_REPO.joinpath("icon").glob(icon_name):
        print(file.name)
        file.unlink(missing_ok=True)

shutil.copytree(
    src=LOCAL_REPO.joinpath("apk"), dst=REMOTE_REPO.joinpath("apk"), dirs_exist_ok=True
)
shutil.copytree(
    src=LOCAL_REPO.joinpath("icon"),
    dst=REMOTE_REPO.joinpath("icon"),
    dirs_exist_ok=True,
)

remote_extensions = load_remote_extensions(REMOTE_REPO.joinpath("index.json"))

with LOCAL_REPO.joinpath("index.json").open() as f:
    local_proto = json_format.Parse(f.read(), index_pb2.ExtensionList())

all_extensions = [
    ext
    for ext in remote_extensions
    if not any(ext.packageName.endswith(f".{module}") for module in to_delete)
]
all_extensions.extend(local_proto.extensions)
all_extensions.sort(key=lambda ext: ext.packageName)

index = index_pb2.Index(
    name="Yūzōnō",
    badgeLabel="Yū",
    signingKey="cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905",
    contact=index_pb2.Contact(
        website="https://yuzono.github.io", discord="https://discord.gg/85MZhUX688"
    ),
    extensionList=index_pb2.ExtensionList(extensions=all_extensions),
)

with REMOTE_REPO.joinpath("index.json").open("w", encoding="utf-8") as f:
    f.write(
        json_format.MessageToJson(
            index,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        )
    )

with REMOTE_REPO.joinpath("index.pb").open("wb") as f:
    f.write(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))


def get_legacy_lang(ext) -> str:
    apk_filename = ext.resources.apkUrl.split("/")[-1]
    lang = LANGUAGE_REGEX.search(apk_filename).group(1)
    if len(ext.sources) == 1:
        source_language = ext.sources[0].language
        if (
            source_language != lang
            and source_language not in {"all", "other"}
            and lang not in {"all", "other"}
        ):
            lang = source_language
    return lang


legacy_json_index = [
    {
        "name": f"Aniyomi: {ext.name}",
        "pkg": ext.packageName,
        "apk": ext.resources.apkUrl.split("/")[-1],
        "lang": get_legacy_lang(ext),
        "code": ext.versionCode,
        "version": ext.versionName,
        "nsfw": 1 if ext.contentWarning > 1 else 0,
        "torrent": 1 if ext.isTorrent else 0,
        "sources": [
            {
                "name": source.name,
                "lang": source.language,
                "id": str(source.id),
                "baseUrl": source.homeUrl,
            }
            for source in ext.sources
        ],
    }
    for ext in all_extensions
]

with REMOTE_REPO.joinpath("index.min.json").open("w", encoding="utf-8") as f:
    json.dump(legacy_json_index, f, ensure_ascii=False, separators=(",", ":"))

with REMOTE_REPO.joinpath("index.html").open("w", encoding="utf-8") as f:
    f.write(
        '<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n'
    )
    for ext in all_extensions:
        apk_escaped = html.escape(ext.resources.apkUrl)
        name_escaped = html.escape(f"Aniyomi: {ext.name}")
        f.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    f.write("</pre>\n</body>\n</html>\n")
