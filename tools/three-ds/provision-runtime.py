import argparse
import re
import subprocess
from pathlib import Path


def provision(bundle: Path, serial: str, package: str):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", package):
        raise ValueError("Invalid Android package")
    files = [bundle / name for name in ("runtime-profile.json", "refresh-trust.json", "client.p12")]
    if not all(path.is_file() for path in files):
        raise ValueError("Incomplete local runtime bundle")
    adb = ["adb", "-s", serial]
    root = subprocess.check_output(adb + ["shell", "id", "-u"], text=True).strip()
    if root != "0":
        raise RuntimeError("Provisioning requires an explicitly rooted adb session")
    app_directory = f"/data/user/0/{package}"
    owner = subprocess.check_output(adb + ["shell", "stat", "-c", "%u:%g", app_directory], text=True).strip()
    if not re.fullmatch(r"[0-9]+:[0-9]+", owner):
        raise RuntimeError("Could not determine installed app ownership")
    target = f"{app_directory}/no_backup/three-ds-profile"
    subprocess.run(adb + ["shell", "mkdir", "-p", target], check=True)
    for path in files:
        destination = f"{target}/{path.name}"
        subprocess.run(adb + ["push", str(path), destination], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(adb + ["shell", "chown", owner, destination], check=True)
        subprocess.run(adb + ["shell", "chmod", "600", destination], check=True)
    subprocess.run(adb + ["shell", "chown", owner, target], check=True)
    subprocess.run(adb + ["shell", "chmod", "700", target], check=True)
    print("Provisioned app-private runtime profile; no service requests made")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    arguments = parser.parse_args()
    provision(arguments.bundle, arguments.serial, arguments.package)
