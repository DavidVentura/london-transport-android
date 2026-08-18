#!/usr/bin/env python3
"""Quick hire runner for the dock. Uses a cached session so there's no slow login.

    python hire.py near <lat> <lng>        # list nearby docks + their station ids (to find the one you're at)
    python hire.py <station_id>            # PREVIEW: begin only, shows confirm page (no bike released)
    python hire.py <station_id> --confirm  # COMMIT: releases a bike, prints the release code

Session/credentials:
  - Reuses session.json (cached login) if present — no re-login needed.
  - Otherwise logs in from .creds (EMAIL=/PASSWORD= lines) and writes session.json.
"""
import sys, pathlib
import corethree_cyclehire as c3

SESSION = pathlib.Path("session.json")
CREDS = pathlib.Path(".creds")


def load_client() -> c3.CoreClient:
    if SESSION.exists():
        return c3.CoreClient.from_session(c3.Session.from_json(SESSION.read_text()))
    kv = dict(l.split("=", 1) for l in CREDS.read_text().splitlines() if "=" in l)
    client = c3.CoreClient(device_id=c3.new_device_id())  # stable id, generated once
    client.login(kv["EMAIL"].strip(), kv["PASSWORD"].strip())
    SESSION.write_text(client.as_session.to_json())
    print(f"[logged in, session cached to {SESSION}]")
    return client


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)

    if sys.argv[1] == "near":
        lat, lng = float(sys.argv[2]), float(sys.argv[3])
        client = load_client()
        docks = sorted(client.find_points(lat, lng, 400), key=lambda d: -d.bikes)
        print(f"\n{'station':<8} {'bikes':>5} {'ebk':>4} {'empty':>5}  name")
        for d in docks:
            print(f"{d.station_id:<8} {d.bikes:>5} {d.ebikes:>4} {d.empty_docks:>5}  {d.name}")
        return

    station_id = sys.argv[1]
    do_confirm = "--confirm" in sys.argv[2:]

    client = load_client()

    info = client.station_info(station_id)
    print(f"\n{info.name}  (station {info.station_id}, terminal {info.terminal_name})")
    print(f"  bikes={info.bikes}  ebikes={info.available_ebikes}  empty_docks={info.empty_docks}  disabled={info.disabled_bikes}")

    print("\n[begin_hire] loading confirm page …")
    try:
        page = client.begin_hire(info.terminal_name)
    except c3.CoreError as e:
        sys.exit(f"begin_hire refused: {e}")
    for n in page:
        if n.type.startswith("Node.FormControls.Label") or n.type == "Node":
            if n.name and n.name != "&#8226;":
                print("   ", n.name)

    if not do_confirm:
        print("\nPREVIEW ONLY — nothing released. Re-run with --confirm to release a bike.")
        return

    print("\n[confirm_hire] RELEASING BIKE …")
    result = client.confirm_hire(info.terminal_name, info.name)

    if isinstance(result, c3.VerificationRequired):
        print(f"\n[new-device verification] {result.message}")
        sms = input("Enter the SMS code: ").strip()
        client.verify_device(result, sms)
        print("[device verified — re-confirming hire]")
        result = client.confirm_hire(info.terminal_name, info.name)
        assert isinstance(result, c3.HireCode), "Expected HireCode, got something else"

    print("\n" + "#" * 44)
    print(f"#   RELEASE CODE:  {result.release_code}")
    print(f"#   {result.detail}")
    print("#" * 44)


if __name__ == "__main__":
    main()
