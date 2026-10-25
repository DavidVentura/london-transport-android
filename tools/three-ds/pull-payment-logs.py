import argparse
import os
import re
import subprocess
from pathlib import Path


def pull(serial: str, package: str, destination: Path):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", package):
        raise ValueError("Invalid Android package")
    adb = ["adb", "-s", serial]
    if subprocess.check_output(adb + ["shell", "id", "-u"], text=True).strip() != "0":
        raise RuntimeError("Export requires an explicitly rooted adb session")
    directory = f"/data/user/0/{package}/no_backup"
    subprocess.run(adb + ["shell", "test", "-d", f"{directory}/payment-logs"], check=True)
    with os.fdopen(os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as output:
        subprocess.run(adb + ["exec-out", "tar", "-C", directory, "-cf", "-", "payment-logs"], stdout=output, check=True)
    print("Exported private payment logs; responses may contain sensitive account or bank data")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    pull(args.serial, args.package, args.output)
