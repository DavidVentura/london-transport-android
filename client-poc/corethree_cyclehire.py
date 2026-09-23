"""
Client for the Santander Cycles (TfL Cycle Hire) backend.

The backend is Corethree's "node" platform: an action is a POST to a node URI,
and each endpoint returns its own fixed shape — a flatjson array of nodes or a
bare JSON object. Every endpoint parses its own shape at the boundary.
"""

from __future__ import annotations

import base64
import hashlib
import json
import re
import time
import urllib.parse
from dataclasses import dataclass, field

import requests
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

BASE_URL = "https://ce-a24.corethree.net/"
REMOTE_HOST = "ce.corethree.net"

_CONTROLVALS_KEY = base64.b64decode("diS1dUbsVQyWSRA5zhAbKJrxYmLa7hD5CVUWc4OZaRs=")
_CONTROLVALS_IV = _CONTROLVALS_KEY[:16]

RETRYABLE_STATUS = {502, 503, 504}


class CoreError(RuntimeError):
    pass


def part_uri_to_url(part_uri: str) -> str:
    if not part_uri.startswith("part://"):
        raise ValueError(f"not a part uri: {part_uri}")
    body = part_uri[len("part://"):]
    # Dots become slashes in the path only; the query string is left untouched.
    if "?" in body:
        path, query = body.split("?", 1)
        return BASE_URL + path.replace(".", "/") + "?" + query
    return BASE_URL + body.replace(".", "/")


def make_userauth(user_node_id: str, auth_token: str) -> str:
    digest = hashlib.sha1((user_node_id + auth_token).encode("us-ascii")).hexdigest()
    return f"{digest}|{user_node_id}"


def encrypt_controlvals(fields: dict[str, str]) -> str:
    form = "&".join(
        f"{urllib.parse.quote(k)}={urllib.parse.quote(v)}"
        for k, v in fields.items()
        if k != "FindLocation-cursor_location"
    )
    cipher = AES.new(_CONTROLVALS_KEY, AES.MODE_CBC, _CONTROLVALS_IV)
    return base64.b64encode(cipher.encrypt(pad(form.encode("utf-8"), AES.block_size))).decode("ascii")


@dataclass(frozen=True)
class Credentials:
    user_node_id: str
    auth_token: str

    @property
    def userauth_header(self) -> str:
        return make_userauth(self.user_node_id, self.auth_token)

    def to_json(self) -> str:
        return json.dumps({"user_node_id": self.user_node_id, "auth_token": self.auth_token})

    @classmethod
    def from_json(cls, s: str) -> "Credentials":
        d = json.loads(s)
        return cls(user_node_id=d["user_node_id"], auth_token=d["auth_token"])


@dataclass(frozen=True)
class Session:
    device_id: str
    credentials: Credentials

    def to_json(self) -> str:
        return json.dumps({
            "device_id": self.device_id,
            "user_node_id": self.credentials.user_node_id,
            "auth_token": self.credentials.auth_token,
        })

    @classmethod
    def from_json(cls, s: str) -> "Session":
        d = json.loads(s)
        return cls(
            device_id=d["device_id"],
            credentials=Credentials(user_node_id=d["user_node_id"], auth_token=d["auth_token"]),
        )


def new_device_id() -> str:
    # The server trusts a device_id after one SMS verification, so generate this
    # once per install and persist it; a fresh id would re-trigger verification.
    import secrets
    return secrets.token_hex(8)


@dataclass(frozen=True)
class Node:
    type: str
    id: str
    name: str
    tags: dict[str, str]
    target_uri: str
    raw: dict

    @classmethod
    def parse(cls, d: dict) -> "Node":
        return cls(
            type=d.get("Type", ""),
            id=d.get("ID", ""),
            name=d.get("Name", ""),
            tags=d.get("Tags", {}),
            target_uri=d.get("TargetUri", ""),
            raw=d,
        )


def parse_nodes(text: str) -> list[Node]:
    arr = json.loads(text)
    if not isinstance(arr, list):
        raise CoreError(f"expected a node array, got {type(arr).__name__}")
    nodes = [Node.parse(d) for d in arr]
    # The server signals failure as a sole error node rather than an HTTP status.
    if len(nodes) == 1 and nodes[0].type == "Node.Internal.Error":
        raise CoreError(nodes[0].name)
    return nodes


@dataclass(frozen=True)
class EBike:
    bike_id: str
    is_reserved: bool
    is_disabled: bool
    current_range_m: float
    battery_pct: float
    max_range_m: float

    @classmethod
    def parse(cls, d: dict) -> "EBike":
        return cls(
            bike_id=d["bikeId"],
            is_reserved=bool(d["isReserved"]),
            is_disabled=bool(d["isDisabled"]),
            current_range_m=d["currentRange"],
            battery_pct=d["batteryPercentage"],
            max_range_m=d["maxRange"],
        )

    @property
    def available(self) -> bool:
        return not self.is_reserved and not self.is_disabled


@dataclass(frozen=True)
class Dock:
    station_id: str
    name: str
    lat: float
    lng: float
    bikes: int
    ebikes: int
    empty_docks: int
    context_uri: str

    @classmethod
    def parse(cls, n: Node) -> "Dock":
        # The FindLCHSPoints tag names are inverted (verified against the Subtitle
        # text): LCHS.Docks is bikes available and LCHS.Bikes is empty docks.
        # LCHS.EBikes is unreliable too — StationInfo.available_ebikes is truthful.
        t = n.tags
        return cls(
            station_id=t["StationID"],
            name=n.name,
            lat=float(n.raw["Lat"]),
            lng=float(n.raw["Long"]),
            bikes=int(t["LCHS.Docks"]),
            ebikes=int(t["LCHS.EBikes"]),
            empty_docks=int(t["LCHS.Bikes"]),
            context_uri=t.get("ClickLinkUri", ""),
        )


@dataclass(frozen=True)
class StationInfo:
    station_id: str
    name: str
    terminal_name: str      # keyed by the hire calls, unlike the numeric station_id
    is_hub: bool
    bikes: int
    ebikes_reported: int    # NumberOfEBikes is unreliable; use available_ebikes
    empty_docks: int
    disabled_bikes: int
    is_favourite: bool
    ebikes: list[EBike]

    @classmethod
    def parse(cls, text: str) -> "StationInfo":
        d = json.loads(text)
        if d.get("status") != "0":
            raise CoreError(f"station info not OK: {d.get('message')!r}")
        s = d["station_info"]
        return cls(
            station_id=s["StationId"],
            name=s["Name"],
            terminal_name=s["TerminalName"],
            is_hub=s["IsHub"] == "1",
            bikes=s["NumberOfBikes"],
            ebikes_reported=s["NumberOfEBikes"],
            empty_docks=s["NumberOfEmptyDocks"],
            disabled_bikes=s["NumberOfBikesDisabled"],
            is_favourite=s["IsFavourite"],
            ebikes=[EBike.parse(e) for e in s["EBikes"]],
        )

    @property
    def available_ebikes(self) -> int:
        return sum(1 for e in self.ebikes if e.available)


@dataclass(frozen=True)
class SubscriptionType:
    id: int
    name: str
    description: str
    amount: float
    max_concurrent_bikes: int
    last_for_days: int
    raw: dict

    @classmethod
    def parse(cls, d: dict) -> "SubscriptionType":
        return cls(
            id=d["subscriptionTypeId"],
            name=d["name"],
            description=d["description"],
            amount=d["amount"],
            max_concurrent_bikes=d["maxNbConcurrentBikes"],
            last_for_days=d["lastForNoDays"],
            raw=d,
        )


@dataclass(frozen=True)
class MarketingPreferences:
    receive_tfl: bool
    receive_santander: bool

    @classmethod
    def parse(cls, text: str) -> "MarketingPreferences":
        d = json.loads(text)
        return cls(
            receive_tfl=bool(d["ReceiveTfLPromotions"]),
            receive_santander=bool(d["ReceiveSantanderPromotions"]),
        )


@dataclass(frozen=True)
class AnnualStatus:
    active: bool
    status: str

    @classmethod
    def parse(cls, text: str) -> "AnnualStatus":
        d = json.loads(text)
        return cls(active=d["AnnualActiveSubscriber"], status=d["SubscriptionStatus"])


@dataclass(frozen=True)
class HireCode:
    release_code: str       # keyed into the dock point's keypad to release a bike
    station: str
    valid_until: str        # the wall-clock "HH:MM" the server prints; "" if absent
    detail: str

    @classmethod
    def parse(cls, nodes: list[Node]) -> "HireCode":
        code_node = next(n for n in nodes if "release code" in n.name.lower() and n.raw.get("Subtitle"))
        bar = next((n for n in nodes if n.id.endswith("_unlockbar")), None)
        detail = bar.name if bar else ""
        m = re.search(r"valid at (.+?) until (\d{1,2}:\d{2})", detail)
        return cls(
            release_code=code_node.raw["Subtitle"],
            station=m.group(1) if m else "",
            valid_until=m.group(2) if m else "",
            detail=detail,
        )


@dataclass(frozen=True)
class VerificationRequired:
    verify_uri: str
    pincode_fields: list[str]
    message: str

    @classmethod
    def detect(cls, nodes: list[Node]) -> "VerificationRequired | None":
        verify_btn = next((n for n in nodes if n.id == "member_next_button_sms"), None)
        if verify_btn is None:
            return None
        fields = sorted(n.id for n in nodes
                        if n.type == "Node.FormControls.TextBox" and n.id.startswith("lchs_pincode_"))
        intro = next((n.name for n in nodes if n.id == "member_sms_desc1"), "Account verification required")
        return cls(verify_uri=verify_btn.target_uri, pincode_fields=fields, message=intro)


@dataclass
class CoreClient:
    device_id: str                       # stable per install; see Session / new_device_id()
    credentials: Credentials | None = None
    version_code: int = 642
    model: str = "LE2123"
    os_version: str = "Android 14"
    package: str = "uk.gov.tfl.cyclehire"
    lat: float | None = None
    lng: float | None = None
    session: requests.Session = field(default_factory=requests.Session)
    retries: int = 6                     # the backend intermittently 504s under load
    timeout: tuple[float, float] = (5.0, 15.0)  # read is kept under the gateway limit

    @classmethod
    def from_session(cls, s: Session, **kw) -> "CoreClient":
        return cls(device_id=s.device_id, credentials=s.credentials, **kw)

    @property
    def as_session(self) -> Session:
        if self.credentials is None:
            raise CoreError("not logged in")
        return Session(device_id=self.device_id, credentials=self.credentials)

    @property
    def user_agent(self) -> str:
        return f"Core/{self.version_code} (Android; {self.model}; {self.os_version}; {self.package})"

    def _post(
        self,
        node_uri: str,
        *,
        params: dict[str, str] | None = None,
        control_vals: dict[str, str] | None = None,
        raw_format: bool = False,
    ) -> str:
        url = part_uri_to_url(node_uri) if node_uri.startswith("part://") else node_uri

        headers = {
            "Content-Type": "application/x-www-form-urlencoded",
            "User-agent": self.user_agent,
            "format": "raw" if (raw_format or "format=raw" in url) else "flatjson",
            "c3-userauth": self.credentials.userauth_header if self.credentials else "",
            "c3-capabilities": "",
            "c3-language": "English",
            "c3-diagnostics": '{"nodestore":"count":"0","bytelength":"0"}',
        }
        if self.lat is not None and self.lng is not None:
            headers["c3-userlat"] = str(self.lat)
            headers["c3-userlong"] = str(self.lng)

        body: dict[str, str] = {
            "c3-clienttime": str(int(time.time())),
            "c3-deviceid": self.device_id,
            "RemoteHost": REMOTE_HOST,
        }
        if params:
            body.update(params)
        if control_vals:
            headers["c3-applysensitivedatacheck"] = "y"
            body["c3-controlvals"] = encrypt_controlvals({**control_vals, "encryptionverificationcheck": "1"})

        # A stuck request hangs until the gateway 504s it, so cap the read below that
        # limit and retry (5xx and client timeouts) to fail fast rather than wait it out.
        last_exc: Exception | None = None
        for attempt in range(self.retries):
            try:
                resp = self.session.post(url, headers=headers, data=body, timeout=self.timeout)
                if resp.status_code not in RETRYABLE_STATUS:
                    resp.raise_for_status()
                    return resp.text
            except (requests.Timeout, requests.ConnectionError) as e:
                last_exc = e
            if attempt < self.retries - 1:
                time.sleep(1)
        if last_exc is not None:
            raise last_exc
        resp.raise_for_status()
        return resp.text

    # ---- read-only, unauthenticated ------------------------------------------------

    def find_points(self, lat: float, lng: float, radius_m: float) -> list[Dock]:
        # The map part needs the viewport as body params or it 500s server-side.
        nodes = parse_nodes(self._post(
            "part://Clients.TfL.FindLCHSPoints_v2?RegionChanged=1",
            params={
                "c3-mapregion-latitude": str(lat),
                "c3-mapregion-longitude": str(lng),
                "c3-mapregion-radius": str(radius_m),
            },
        ))
        return [Dock.parse(n) for n in nodes if n.id != "lchs_root_v2_map"]

    def station_info(self, station_id: str) -> StationInfo:
        return StationInfo.parse(self._post(
            f"{BASE_URL}Clients/TfL/EBIKES/Phase1/GetCurrentStationInfo?stationid={station_id}",
            raw_format=True,
        ))

    def subscription_types(self) -> list[SubscriptionType]:
        arr = json.loads(self._post(f"{BASE_URL}Clients/TfL/EBikePhase2/EBIKES/GetSubscriptionTypes"))
        return [SubscriptionType.parse(d) for d in arr]

    def dock_context(self, station_id: str, hub: bool = False) -> list[Node]:
        return parse_nodes(self._post(
            f"part://Clients.TfL.GenerateLCHSDockContextView_V3?ID={station_id}&hub={hub}"
        ))

    # ---- authentication ------------------------------------------------------------

    def login(self, email: str, password: str) -> Credentials:
        # Two POSTs: ProcessLogin returns a confirmation page whose Continue button
        # targets ConfirmationPageAction, which returns the Node.Internal.User.
        confirm = parse_nodes(self._post(
            "part://Clients.TfL.TSUF.ProcessLogin?terminal=&terminalname=&navigateBackToID=",
            control_vals={"login_email": email, "login_password": password},
        ))
        button = next(n for n in confirm if n.id == "login_continue_button")
        user_nodes = parse_nodes(self._post(button.target_uri))
        user = user_nodes[0]
        if user.type != "Node.Internal.User" or "AuthToken" not in user.raw:
            raise CoreError("login did not yield a Node.Internal.User with an AuthToken")
        self.credentials = Credentials(user_node_id=user.id, auth_token=user.raw["AuthToken"])
        return self.credentials

    # ---- hire ----------------------------------------------------------------------

    def begin_hire(self, terminal_name: str) -> list[Node]:
        # Non-committal: this only builds the confirm page; nothing is reserved and
        # there is no cancel. confirm_hire is the sole committing call.
        nodes = parse_nodes(self._post(
            f"part://Clients.TfL.EBikePhase2.BeginMemberHire?TerminalName={terminal_name}"
        ))
        # An empty station replies with a lone SimpleResponse and no confirm button.
        if len(nodes) == 1 and nodes[0].type == "Node.Internal.SimpleResponse":
            raise CoreError(nodes[0].name)
        return nodes

    def confirm_hire(self, terminal_name: str, point_name: str, nb_bikes: int = 1) -> "HireCode | VerificationRequired":
        # Committing: releases a physical bike. Returns VerificationRequired instead
        # of a HireCode when this device_id is untrusted (verify_device then retry).
        point = point_name.replace("&", "and")
        nodes = parse_nodes(self._post(
            "part://Clients.TfL.EBikePhase2.ConfirmMemberHire"
            f"?TerminalName={terminal_name}&PointName={point}&LCHS_Confirm=1&nbBikes={nb_bikes}"
        ))
        challenge = VerificationRequired.detect(nodes)
        if challenge is not None:
            return challenge
        return HireCode.parse(nodes)

    def verify_device(self, challenge: VerificationRequired, sms_code: str) -> None:
        digits = list(sms_code.strip())
        if len(digits) != len(challenge.pincode_fields):
            raise CoreError(f"expected {len(challenge.pincode_fields)} SMS digits, got {len(digits)}")
        control_vals = dict(zip(challenge.pincode_fields, digits))
        result = parse_nodes(self._post(challenge.verify_uri, control_vals=control_vals))
        ack = result[0]
        if "verified" not in ack.name.lower():
            raise CoreError(f"device verification failed: {ack.name!r}")

    # ---- read-only, authenticated --------------------------------------------------

    def marketing_preferences(self) -> MarketingPreferences:
        return MarketingPreferences.parse(self._post(f"{BASE_URL}Clients/TfL/RIDERS/GetMarketingPreferences"))

    def is_annual_active(self) -> AnnualStatus:
        return AnnualStatus.parse(self._post(f"{BASE_URL}Clients/TfL/RIDERS/Phase3/IsAnnualActiveLCHSUser"))
