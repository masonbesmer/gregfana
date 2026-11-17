"""Check the distributable metadata needed for production Forge/Mixin startup."""
import json
import sys
import zipfile
from pathlib import Path

jar = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/libs/gregfana-0.1.0.jar")
with zipfile.ZipFile(jar) as archive:
    manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
    assert "FMLCorePlugin: dev.gregfana.EarlyMixins" in manifest
    assert "FMLCorePluginContainsFMLMod: true" in manifest
    assert "MixinConfigs: mixins.gregfana.json" in manifest
    for name in ("mixins.gregfana.json", "mixins.gregfana.early.json", "mixins.gregfana.late.json"):
        config = json.loads(archive.read(name))
        assert config["required"]
        assert config["refmap"] in archive.namelist()
    refmap = json.loads(archive.read("mixins.gregfana.refmap.json"))
    assert refmap["mappings"]["dev/gregfana/mixin/CraftingMixin"]
    info = json.loads(archive.read("mcmod.info"))["modList"][0]
    assert info["modid"] == "gregfana" and info["version"] == "0.1.0"
    assert not any("SmokeExporter" in n or n.startswith("org/junit/") for n in archive.namelist())
    for name in archive.namelist():
        if name.endswith(".class"):
            assert int.from_bytes(archive.read(name)[6:8], "big") == 52, name
print(f"Production metadata, refmap, and Java 8 bytecode verified: {jar}")
