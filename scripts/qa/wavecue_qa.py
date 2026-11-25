#!/usr/bin/env python3
"""Drive WaveCue on a device or emulator the way a person would.

This is the exploratory half of the test setup: it talks to `adb`, reads the
real accessibility tree, taps real coordinates and records evidence. The
committed instrumented tests cover the repeatable assertions; this harness is
for looking around, reproducing a report, and checking a flow before turning it
into a test.

    python3 scripts/qa/wavecue_qa.py setup            # fixtures + install + grant + launch
    python3 scripts/qa/wavecue_qa.py dump             # what is on screen right now
    python3 scripts/qa/wavecue_qa.py tap "Choose a folder"
    python3 scripts/qa/wavecue_qa.py pick-folder WaveCueFixtures
    python3 scripts/qa/wavecue_qa.py shot library
    python3 scripts/qa/wavecue_qa.py media
    python3 scripts/qa/wavecue_qa.py logcat --errors

Every command accepts --serial to pick a device and --adb to point at a binary.
Evidence (screenshots, UI dumps) lands in build/qa-evidence/<run>/.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

PACKAGE = "com.wavecue.app"
ACTIVITY = f"{PACKAGE}/.MainActivity"
FIXTURE_FOLDER = "WaveCueFixtures"
REMOTE_FIXTURES = f"/sdcard/{FIXTURE_FOLDER}"
ROOT = Path(__file__).resolve().parents[2]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
EVIDENCE_ROOT = ROOT / "build/qa-evidence"

KEYCODES = {
    "home": "KEYCODE_HOME",
    "back": "KEYCODE_BACK",
    "enter": "KEYCODE_ENTER",
    "tab": "KEYCODE_TAB",
    "space": "KEYCODE_SPACE",
    "delete": "KEYCODE_DEL",
    "play": "KEYCODE_MEDIA_PLAY",
    "pause": "KEYCODE_MEDIA_PAUSE",
    "playpause": "KEYCODE_MEDIA_PLAY_PAUSE",
    "next": "KEYCODE_MEDIA_NEXT",
    "prev": "KEYCODE_MEDIA_PREVIOUS",
    "stop": "KEYCODE_MEDIA_STOP",
    "wake": "KEYCODE_WAKEUP",
    "sleep": "KEYCODE_SLEEP",
    "volumeup": "KEYCODE_VOLUME_UP",
    "volumedown": "KEYCODE_VOLUME_DOWN",
}


def _which_adb(explicit: str | None) -> str:
    if explicit:
        return explicit
    found = shutil.which("adb")
    if found:
        return found
    for candidate in (
        os.environ.get("ANDROID_HOME", ""),
        os.environ.get("ANDROID_SDK_ROOT", ""),
        str(Path.home() / "android-sdk"),
    ):
        if candidate:
            path = Path(candidate) / "platform-tools/adb"
            if path.exists():
                return str(path)
    raise SystemExit("adb not found; pass --adb or put platform-tools on PATH")


@dataclass
class Node:
    text: str
    desc: str
    resource_id: str
    cls: str
    clickable: bool
    bounds: tuple[int, int, int, int]

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    @property
    def label(self) -> str:
        return self.text or self.desc

    def __str__(self) -> str:
        flags = "".join(
            (
                "T" if self.text else "-",
                "D" if self.desc else "-",
                "C" if self.clickable else "-",
            )
        )
        rid = f" id={self.resource_id.split('/')[-1]}" if self.resource_id else ""
        return f"[{flags}] {self.cls.split('.')[-1]:<12} {self.label!r:<48} {self.bounds}{rid}"


class Device:
    def __init__(self, adb: str, serial: str | None):
        self.adb = adb
        self.serial = serial
        self.run_dir = EVIDENCE_ROOT / datetime.now().strftime("%Y%m%d-%H%M%S")
        self._run_dir_ready = False

    # ---------------------------------------------------------------- plumbing
    def _base(self) -> list[str]:
        cmd = [self.adb]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def shell(self, command: str, check: bool = True) -> str:
        result = subprocess.run(
            self._base() + ["shell", command],
            capture_output=True,
            text=True,
            errors="replace",
        )
        if check and result.returncode != 0:
            raise SystemExit(f"adb shell failed ({result.returncode}): {command}\n{result.stderr}")
        return result.stdout

    def host(self, *args: str, check: bool = True) -> subprocess.CompletedProcess:
        result = subprocess.run(self._base() + list(args), capture_output=True, text=True, errors="replace")
        if check and result.returncode != 0:
            raise SystemExit(f"adb {' '.join(args)} failed ({result.returncode})\n{result.stderr}")
        return result

    def raw(self, *args: str) -> bytes:
        return subprocess.run(self._base() + list(args), capture_output=True).stdout

    def run_dir(self) -> Path:
        if not self._run_dir_ready:
            self.run_dir.mkdir(parents=True, exist_ok=True)
            self._run_dir_ready = True
        return self.run_dir

    # ------------------------------------------------------------- ui reading
    def dump(self, retries: int = 3) -> list[Node]:
        last = ""
        for attempt in range(retries):
            self.shell("uiautomator dump /sdcard/window_dump.xml", check=False)
            xml = self.shell("cat /sdcard/window_dump.xml", check=False)
            if xml.lstrip().startswith("<?xml"):
                try:
                    return self._parse(xml)
                except ET.ParseError as error:  # transient partial file
                    last = str(error)
            else:
                last = xml.strip()[:200]
            time.sleep(0.4 + 0.4 * attempt)
        raise SystemExit(f"could not read the UI hierarchy: {last}")

    @staticmethod
    def _parse(xml: str) -> list[Node]:
        nodes: list[Node] = []
        root = ET.fromstring(xml)
        for element in root.iter("node"):
            bounds = re.findall(r"-?\d+", element.get("bounds", ""))
            if len(bounds) != 4:
                continue
            nodes.append(
                Node(
                    text=element.get("text", ""),
                    desc=element.get("content-desc", ""),
                    resource_id=element.get("resource-id", ""),
                    cls=element.get("class", ""),
                    clickable=element.get("clickable") == "true",
                    bounds=tuple(int(v) for v in bounds),  # type: ignore[arg-type]
                )
            )
        return nodes

    @staticmethod
    def match(nodes: list[Node], needle: str, exact: bool, include_desc: bool = True) -> list[Node]:
        hits = []
        for node in nodes:
            values = [node.text] + ([node.desc] if include_desc else [])
            for value in values:
                if not value:
                    continue
                if (value == needle) if exact else (needle.lower() in value.lower()):
                    hits.append(node)
                    break
        return hits

    def require(self, nodes: list[Node], needle: str, exact: bool) -> Node:
        hits = self.match(nodes, needle, exact)
        if not hits:
            print(f"not on screen: {needle!r}. Visible:", file=sys.stderr)
            for node in nodes:
                if node.label or node.clickable:
                    print(f"  {node}", file=sys.stderr)
            raise SystemExit(2)
        clickable = [n for n in hits if n.clickable]
        return (clickable or hits)[0]

    # ---------------------------------------------------------------- actions
    def tap(self, x: int, y: int) -> None:
        self.shell(f"input tap {x} {y}")

    def long_press(self, x: int, y: int, duration_ms: int = 700) -> None:
        self.shell(f"input swipe {x} {y} {x} {y} {duration_ms}")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
        self.shell(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}")

    def type_text(self, text: str) -> None:
        escaped = text.replace("%", "%%").replace(" ", "%s")
        escaped = escaped.replace("'", "\\'").replace('"', '\\"')
        self.shell(f"input text '{escaped}'")

    def key(self, name: str) -> None:
        code = KEYCODES.get(name.lower(), name)
        self.shell(f"input keyevent {code}")

    def screenshot(self, name: str | None = None) -> Path:
        stamp = name or datetime.now().strftime("screen-%H%M%S")
        if not stamp.endswith(".png"):
            stamp += ".png"
        target = self.run_dir() / stamp
        target.write_bytes(self.raw("exec-out", "screencap", "-p"))
        print(target)
        return target

    def save_dump(self, name: str) -> Path:
        target = self.run_dir() / f"{name}.xml"
        target.write_text(self.shell("cat /sdcard/window_dump.xml", check=False))
        print(target)
        return target

    # ------------------------------------------------------------------- app
    def install(self, apk: Path) -> None:
        if not apk.exists():
            raise SystemExit(f"APK not found: {apk}\nRun ./gradlew assembleDebug first.")
        self.host("install", "-r", "-g", str(apk))

    def grant_notifications(self) -> None:
        self.shell(
            f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS",
            check=False,
        )

    def launch(self) -> None:
        self.shell(f"am start -W -n {ACTIVITY}")

    def stop(self) -> None:
        self.shell(f"am force-stop {PACKAGE}")

    def push_fixtures(self, source: Path) -> None:
        if not source.exists():
            raise SystemExit(
                f"fixtures not found: {source}\nRun: python3 scripts/qa/make_fixtures.py --out build/qa-fixtures"
            )
        self.shell(f"rm -rf {REMOTE_FIXTURES}")
        self.shell(f"mkdir -p {REMOTE_FIXTURES}")
        self.host("push", f"{source}/.", f"{REMOTE_FIXTURES}/")
        listing = self.shell(f"ls -1 {REMOTE_FIXTURES}")
        print(listing, end="")

    # -------------------------------------------------------------- documents
    def pick_folder(self, name: str, timeout_s: float = 20.0) -> None:
        """Walk DocumentsUI to `name` and grant access. Mirrors what a person does."""
        self._wait_for_package("com.android.documentsui", timeout_s)
        nodes = self.dump()
        drawer = self.match(nodes, "Show roots", exact=False) or self.match(nodes, "Open navigation drawer", exact=False)
        if drawer:
            self.tap(*drawer[0].center)
            time.sleep(0.8)
            nodes = self.dump()
        storage = (
            self.match(nodes, "Internal storage", exact=False)
            or self.match(nodes, "sdk_gphone", exact=False)
            or self.match(nodes, "emulator", exact=False)
        )
        if storage:
            self.tap(*storage[0].center)
            time.sleep(0.8)
            nodes = self.dump()
        if not self.match(nodes, name, exact=True):
            self._scroll_until(name)
            nodes = self.dump()
        self.tap(*self.require(nodes, name, exact=True).center)
        time.sleep(0.8)
        nodes = self.dump()
        use = self.match(nodes, "Use this folder", exact=False)
        if not use:
            use = [n for n in nodes if n.resource_id.endswith("action_menu_select")]
        if not use:
            raise SystemExit("could not find 'Use this folder' in the picker")
        self.tap(*use[0].center)
        time.sleep(0.8)
        nodes = self.dump()
        allow = self.match(nodes, "Allow", exact=False) or [
            n for n in nodes if n.resource_id.endswith("button1")
        ]
        if allow:
            self.tap(*allow[0].center)
        time.sleep(0.5)

    def save_file(self, timeout_s: float = 20.0) -> None:
        """Accept the current CreateDocument dialog with its prefilled name."""
        self._wait_for_package("com.android.documentsui", timeout_s)
        nodes = self.dump()
        save = self.match(nodes, "Save", exact=True) or [
            n for n in nodes if n.resource_id.endswith("button1")
        ]
        if not save:
            raise SystemExit("could not find 'Save' in the document dialog")
        self.tap(*save[0].center)
        time.sleep(0.8)
        nodes = self.dump()
        replace = self.match(nodes, "Replace", exact=False) or self.match(nodes, "Overwrite", exact=False)
        if replace:
            self.tap(*replace[0].center)

    def _scroll_until(self, needle: str, attempts: int = 6) -> None:
        for _ in range(attempts):
            nodes = self.dump()
            if self.match(nodes, needle, exact=True):
                return
            scrollable = [n for n in nodes if n.clickable and n.bounds[2] - n.bounds[0] > 600]
            if scrollable:
                x1, y1, x2, y2 = scrollable[0].bounds
                self.swipe((x1 + x2) // 2, int(y2 * 0.8), (x1 + x2) // 2, int(y1 + (y2 - y1) * 0.2), 250)
            else:
                self.swipe(540, 1600, 540, 600, 250)
            time.sleep(0.6)

    def _wait_for_package(self, package: str, timeout_s: float) -> None:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            top = self.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'", check=False)
            if package in top:
                time.sleep(0.6)
                return
            time.sleep(0.4)
        raise SystemExit(f"{package} did not come to the foreground")

    # ------------------------------------------------------------- diagnostics
    def media_session(self) -> str:
        return self.shell("dumpsys media_session | grep -A 12 'com.wavecue.app'", check=False)

    def top_activity(self) -> str:
        return self.shell("dumpsys activity activities | grep -m1 ResumedActivity", check=False).strip()

    def logcat(self, lines: int = 200, errors_only: bool = False, clear: bool = False) -> str:
        if clear:
            self.shell("logcat -c", check=False)
            return ""
        args = ["logcat", "-d", "-t", str(lines), "-v", "brief"]
        if errors_only:
            args += ["*:E"]
        return self.host(*args).stdout


# ------------------------------------------------------------------ commands
def cmd_setup(device: Device, args: argparse.Namespace) -> None:
    fixtures = Path(args.fixtures)
    if args.generate or not fixtures.exists():
        subprocess.run(
            [sys.executable, str(ROOT / "scripts/qa/make_fixtures.py"), "--out", str(fixtures)],
            check=True,
        )
    device.install(Path(args.apk))
    device.grant_notifications()
    device.push_fixtures(fixtures)
    device.shell("settings put global window_animation_scale 0", check=False)
    device.shell("settings put global transition_animation_scale 0", check=False)
    device.shell("settings put global animator_duration_scale 0", check=False)
    device.launch()
    print("ready")


def cmd_dump(device: Device, args: argparse.Namespace) -> None:
    nodes = device.dump()
    if args.save:
        device.save_dump(args.save)
    for node in nodes:
        if node.label or node.clickable:
            print(node)


def cmd_find(device: Device, args: argparse.Namespace) -> None:
    nodes = device.dump()
    hits = device.match(nodes, args.text, args.exact)
    if not hits:
        raise SystemExit(1)
    for node in hits:
        print(node)


def cmd_tap(device: Device, args: argparse.Namespace) -> None:
    if args.xy:
        device.tap(*args.xy)
        return
    nodes = device.dump()
    if args.id:
        hits = [n for n in nodes if n.resource_id.endswith(args.id)]
        if not hits:
            raise SystemExit(f"no view with id ending in {args.id!r}")
        device.tap(*hits[0].center)
        return
    if not args.text:
        raise SystemExit("tap needs TEXT, --id or --xy X Y")
    device.tap(*device.require(nodes, args.text, args.exact).center)


def cmd_wait(device: Device, args: argparse.Namespace) -> None:
    deadline = time.time() + args.timeout
    while time.time() < deadline:
        try:
            if device.match(device.dump(), args.text, args.exact):
                print(f"found {args.text!r}")
                return
        except SystemExit:
            pass
        time.sleep(0.5)
    device.screenshot("timeout")
    raise SystemExit(f"timed out after {args.timeout}s waiting for {args.text!r}")


def cmd_pick_folder(device: Device, args: argparse.Namespace) -> None:
    device.pick_folder(args.name)


def cmd_media(device: Device, args: argparse.Namespace) -> None:
    print(device.media_session() or "(no media session for WaveCue)")


def cmd_state(device: Device, args: argparse.Namespace) -> None:
    print(device.top_activity())
    print(device.shell(f"pidof {PACKAGE}", check=False).strip() or "not running")


def cmd_notifications(device: Device, args: argparse.Namespace) -> None:
    device.shell("cmd statusbar expand-notifications")
    time.sleep(1.0)
    nodes = device.dump()
    for node in nodes:
        if node.label:
            print(node)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--adb", default=None, help="path to the adb binary")
    parser.add_argument("--serial", default=None, help="device serial (default: only attached device)")
    sub = parser.add_subparsers(dest="command", required=True)

    def add(name: str, func, help_text: str):
        item = sub.add_parser(name, help=help_text)
        item.set_defaults(func=func)
        return item

    setup = add("setup", cmd_setup, "build fixtures, install, grant, push, launch")
    setup.add_argument("--apk", default=str(APK))
    setup.add_argument("--fixtures", default=str(ROOT / "build/qa-fixtures"))
    setup.add_argument("--generate", action="store_true", help="rebuild fixtures even if they exist")

    add("devices", lambda d, a: print(d.host("devices", "-l").stdout), "list adb devices")

    dump = add("dump", cmd_dump, "print the accessibility tree")
    dump.add_argument("--save", default=None, help="also save the XML under the evidence folder")

    find = add("find", cmd_find, "print nodes matching text or content description")
    find.add_argument("text")
    find.add_argument("--exact", action="store_true")

    tap = add("tap", cmd_tap, "tap a node by text, id suffix, or coordinates")
    tap.add_argument("text", nargs="?")
    tap.add_argument("--exact", action="store_true")
    tap.add_argument("--id", default=None)
    tap.add_argument("--xy", nargs=2, type=int, metavar=("X", "Y"))

    wait = add("wait", cmd_wait, "wait until text appears")
    wait.add_argument("text")
    wait.add_argument("--timeout", type=float, default=15.0)
    wait.add_argument("--exact", action="store_true")

    add("pick-folder", cmd_pick_folder, "walk the system folder picker and grant access").add_argument("name")

    add("save-file", lambda d, a: d.save_file(), "accept the current CreateDocument dialog")

    swipe = add("swipe", lambda d, a: d.swipe(a.x1, a.y1, a.x2, a.y2, a.ms), "swipe between points")
    swipe.add_argument("x1", type=int)
    swipe.add_argument("y1", type=int)
    swipe.add_argument("x2", type=int)
    swipe.add_argument("y2", type=int)
    swipe.add_argument("--ms", type=int, default=300)

    longpress = add("longpress", lambda d, a: d.long_press(a.x, a.y), "long-press a point")
    longpress.add_argument("x", type=int)
    longpress.add_argument("y", type=int)

    add("type", lambda d, a: d.type_text(a.text), "type into the focused field").add_argument("text")
    add("key", lambda d, a: d.key(a.name), "press a key (home, back, playpause, next, ...)").add_argument("name")

    shot = add("shot", lambda d, a: d.screenshot(a.name), "screenshot into the evidence folder")
    shot.add_argument("name", nargs="?")

    add("launch", lambda d, a: d.launch(), "start the app")
    add("stop", lambda d, a: d.stop(), "force-stop the app")
    add("grant", lambda d, a: d.grant_notifications(), "grant notification permission")
    add("state", cmd_state, "show the resumed activity and pid")
    add("media", cmd_media, "show the WaveCue media session")
    add("notifications", cmd_notifications, "open the shade and print its contents")

    log = add("logcat", lambda d, a: print(d.logcat(a.lines, a.errors, a.clear)), "read logcat")
    log.add_argument("--lines", type=int, default=200)
    log.add_argument("--errors", action="store_true")
    log.add_argument("--clear", action="store_true")

    return parser


def main() -> int:
    args = build_parser().parse_args()
    device = Device(_which_adb(args.adb), args.serial)
    result = args.func(device, args)
    return 0 if result is None else int(result)


if __name__ == "__main__":
    raise SystemExit(main())
