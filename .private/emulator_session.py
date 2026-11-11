"""Private Android sessions contained by the caller's existing guard Job and output root.

Shared image files are verified and used without -writable-system. Each session has its own
AVD, userdata, cache, log and ADB server. This module never admits a nested Job.
"""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile
from dataclasses import dataclass

SDK = (Path(os.environ.get("ANDROID_HOME", "/opt/android/sdk")) if sys.platform.startswith("linux")
       else Path(r"C:\Users\User\AppData\Local\Android\Sdk"))
IMAGE_URL = "https://dl.google.com/android/repository/sys-img/android/x86_64-35_r02.zip"
IMAGE_SIZE = 782404023
IMAGE_SHA1 = "2d857d170c0d1b827149565da34b3383e5306f7f"
IMAGE_EXPANDED_LIMIT = 4 * 1024**3
IMAGE_MARKER = ".codex-system-image.json"


def sdk_binary(sdk, directory, name):
    return Path(sdk) / directory / (name + (".exe" if os.name == "nt" else ""))


@dataclass(frozen=True)
class SessionPorts:
    adb: int
    console: int
    grpc: int
    bridge: int

    @property
    def serial(self):
        return f"emulator-{self.console}"

    @property
    def all(self):
        return self.adb, self.console, self.console + 1, self.grpc, self.bridge


def resolve_slot(slot=None, environ=None):
    env = os.environ if environ is None else environ
    value = env.get("CODEX_EMULATOR_SLOT", "0") if slot is None else slot
    if isinstance(value, bool) or str(value) not in ("0", "1"):
        raise ValueError("Emulator slot must be 0 or 1")
    return int(value)


def session_ports(slot=None, environ=None):
    slot = resolve_slot(slot, environ)
    return SessionPorts(5041 + slot, 5580 + 2 * slot, 8554 + 2 * slot, 8877 + slot)


def load_manifest(project, environ=None):
    env = os.environ if environ is None else environ
    override = env.get("CODEX_EMULATOR_MANIFEST")
    path = Path(override) if override else Path(project).parent / "tor-probe-artifact.json"
    if override and not path.is_absolute():
        raise ValueError("CODEX_EMULATOR_MANIFEST must be absolute")
    result = json.loads(path.read_text())
    if not isinstance(result, dict):
        raise ValueError("Emulator artifact manifest must be an object")
    return result


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify_image(system):
    system = Path(system).resolve()
    marker = json.loads((system / IMAGE_MARKER).read_text())
    if marker.get("archiveSha1") != IMAGE_SHA1 or marker.get("archiveBytes") != IMAGE_SIZE:
        raise ValueError("Shared image does not identify the pinned verified archive")
    files = marker.get("files")
    if not isinstance(files, dict) or "system.img" not in files or not files:
        raise ValueError("Shared image verification manifest is incomplete")
    actual = {path.relative_to(system).as_posix() for path in system.rglob("*")
              if path.is_file() and path.name != IMAGE_MARKER}
    if actual != set(files):
        raise ValueError("Shared image contains unverified files")
    total = 0
    for relative, expected in files.items():
        path = (system / relative).resolve()
        if not path.is_relative_to(system) or not path.is_file():
            raise ValueError("Shared image verification path escapes its root")
        total += path.stat().st_size
        if total > IMAGE_EXPANDED_LIMIT or _sha256(path) != expected:
            raise ValueError("Shared image integrity mismatch")

    return system


def prepare_image(root, environ=None):
    """Return system.img's directory; an override must be produced by this API."""
    env = os.environ if environ is None else environ
    override = env.get("CODEX_EMULATOR_IMAGE")
    if override:
        system = Path(override)
        if not system.is_absolute():
            raise ValueError("CODEX_EMULATOR_IMAGE must be absolute")
        return verify_image(system)
    root = Path(root).resolve()
    if root.parent == root:
        raise ValueError("Image output must not be a drive root")
    root.mkdir(parents=True, exist_ok=True)
    image = root / "image"
    if image.exists():
        systems = list(image.rglob("system.img"))
        if len(systems) != 1:
            raise ValueError("Existing image is incomplete or ambiguous")
        return verify_image(systems[0].parent)
    archive_path = root / "android-image.zip"
    if archive_path.exists():
        raise ValueError("Image archive path already exists")
    try:
        digest = hashlib.sha1()
        total = 0
        with urllib.request.urlopen(IMAGE_URL, timeout=30) as response, archive_path.open("xb") as out:
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > IMAGE_SIZE:
                    raise ValueError("Image archive exceeds official size")
                digest.update(chunk)
                out.write(chunk)
        if total != IMAGE_SIZE or digest.hexdigest() != IMAGE_SHA1:
            raise ValueError("Official image integrity mismatch")
        image.mkdir()
        with zipfile.ZipFile(archive_path) as archive:
            if sum(item.file_size for item in archive.infolist()) > IMAGE_EXPANDED_LIMIT:
                raise ValueError("Image expanded size exceeds budget")
            for item in archive.infolist():
                if not (image / item.filename).resolve().is_relative_to(image.resolve()):
                    raise ValueError("Image archive path escapes output")
            archive.extractall(image)
        systems = list(image.rglob("system.img"))
        if len(systems) != 1:
            raise ValueError("Official archive must contain one system image")
        system = systems[0].parent
        files = {path.relative_to(system).as_posix(): _sha256(path)
                 for path in system.rglob("*") if path.is_file()}
        (system / IMAGE_MARKER).write_text(json.dumps({"archiveSha1": IMAGE_SHA1,
            "archiveBytes": IMAGE_SIZE, "files": files}, indent=2) + "\n")

        print("Verified disposable Android image extracted.", flush=True)
        return system
    finally:
        if archive_path.exists():
            archive_path.unlink()


class PrivateAdb:
    def __init__(self, sdk, ports, environment):
        self.command_base = [str(sdk_binary(sdk, "platform-tools", "adb")),
                             "-P", str(ports.adb), "-s", ports.serial]
        self.environment = environment
        self.emulator = None
        self.bridge_port = ports.bridge

    def __call__(self, *args, timeout=30, check=True):
        return subprocess.run([*self.command_base, *args], env=self.environment,
                              capture_output=True, timeout=timeout, check=check)


def _port_closed(port, timeout=10):
    deadline = time.monotonic() + timeout
    while True:
        with socket.socket() as probe:
            probe.settimeout(0.2)
            if probe.connect_ex(("127.0.0.1", port)) != 0:
                return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.1)


class EmulatorSession:
    def __init__(self, root, guest_memory=1536, slot=None, sdk=SDK, system=None, environ=None):
        self.base_environment = dict(os.environ if environ is None else environ)
        self.root = Path(root).resolve()
        allowed = self.base_environment.get("CODEX_GUARD_OUTPUT")
        if not allowed or not self.root.is_relative_to(Path(allowed).resolve()) or self.root.parent == self.root:
            raise ValueError("Session state must stay inside the active guard output root")
        if isinstance(guest_memory, bool) or not isinstance(guest_memory, int) or not 512 <= guest_memory <= 3072:
            raise ValueError("Guest memory must be an integer from 512 through 3072 MiB")
        self.guest_memory = guest_memory
        self.slot = resolve_slot(slot, self.base_environment)
        self.ports = session_ports(self.slot)
        self.sdk = Path(sdk)
        self.gpu = "lavapipe" if sys.platform.startswith("linux") else "host"
        self.system = Path(system).resolve() if system else None
        self.avd_name = "CodexTorProbe" if self.slot == 0 else "CodexTorProbeSlot1"
        home = self.root / "android"
        self.avds = home / "avd"
        self.environment = dict(self.base_environment, ANDROID_HOME=str(self.sdk),
            ANDROID_SDK_ROOT=str(self.sdk), ANDROID_USER_HOME=str(home), ANDROID_EMULATOR_HOME=str(home),
            ANDROID_AVD_HOME=str(self.avds), ANDROID_ADB_SERVER_PORT=str(self.ports.adb),
            ADB_SERVER_SOCKET=f"tcp:localhost:{self.ports.adb}", ANDROID_EMU_ENABLE_CRASH_REPORTING="0",
            TEMP=str(self.root / "tmp"), TMP=str(self.root / "tmp"))
        self.adb = PrivateAdb(self.sdk, self.ports, self.environment)
        self.process = None
        self.log = None
        self.adb_started = False
        self.lock = None
        self.cleanup = {}
        self._closed = False

    def command(self):
        return [str(sdk_binary(self.sdk, "emulator", "emulator")), "-avd", self.avd_name,
            "-port", str(self.ports.console), "-grpc", str(self.ports.grpc), "-no-window",
            "-partition-size", "2047", "-feature", "-QuickbootFileBacked,-Vulkan", "-no-snapshot",
            "-no-boot-anim", "-no-audio", "-memory", str(self.guest_memory), "-cores", "2", "-gpu", self.gpu,
            "-data", str(self.root / "userdata.img"), "-cache", str(self.root / "cache.img")]

    def __enter__(self):
        if self._closed:
            raise RuntimeError("Emulator session is already closed")
        self.root.mkdir(parents=True, exist_ok=True)
        (self.root / "tmp").mkdir(exist_ok=True)
        self.lock = (self.root / ".emulator-session.lock").open("x")
        try:
            self.system = verify_image(self.system) if self.system else prepare_image(self.root, self.base_environment)
            for port in self.ports.all:
                with socket.socket() as probe:
                    probe.bind(("127.0.0.1", port))
            avd = self.avds / (self.avd_name + ".avd")
            avd.mkdir(parents=True)
            config = {"abi.type": "x86_64", "hw.cpu.arch": "x86_64", "image.sysdir.1": str(self.system),
                "tag.id": "default", "hw.lcd.width": "1080", "hw.lcd.height": "1920", "hw.lcd.density": "420",
                "hw.gpu.enabled": "yes", "hw.gpu.mode": self.gpu, "disk.cachePartition": "yes",
                "avd.id": self.avd_name, "avd.name": self.avd_name, "hw.ramSize": str(self.guest_memory),
                "hw.cpu.ncore": "2", "hw.sdCard": "no", "hw.camera.back": "none", "hw.camera.front": "none",
                "hw.keyboard": "yes", "fastboot.forceColdBoot": "yes", "fastboot.forceFastBoot": "no",
                "firstboot.saveToLocalSnapshot": "no", "disk.dataPartition.size": "2G",
                "disk.dataPartition.path": str(self.root / "userdata.img"),
                "disk.cachePartition.path": str(self.root / "cache.img")}
            (avd / "config.ini").write_text("".join(f"{key}={value}\n" for key, value in config.items()))
            (self.avds / (self.avd_name + ".ini")).write_text(
                f"avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-35\n")
            self.log = (self.root / "emulator.log").open("wb")
            self.adb_started = True
            subprocess.run([str(sdk_binary(self.sdk, "platform-tools", "adb")), "-P", str(self.ports.adb), "start-server"],
                env=self.environment, capture_output=True, check=True, timeout=15)
            command = self.command()
            self.process = subprocess.Popen(command, env=self.environment, stdout=self.log, stderr=self.log,
                creationflags=0x08000000 if os.name == "nt" else 0)
            self.adb.emulator = self.process
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                if self.process.poll() is not None:
                    raise RuntimeError("Emulator exited: " + (self.root / "emulator.log").read_text(errors="replace")[-2500:])
                if self.adb("shell", "getprop", "sys.boot_completed", check=False, timeout=10).stdout.strip() == b"1":
                    break
                time.sleep(2)
            else:
                raise RuntimeError("Emulator boot deadline exceeded")
            self.guest_data_before = self.adb("shell", "df", "-k", "/data").stdout.decode().strip()
            self.guest_data_partition_kib = int(self.guest_data_before.splitlines()[-1].split()[1])
            if self.guest_data_partition_kib > 6500000:
                raise ValueError("Guest data partition exceeds reviewed 6GiB profile")
            return self
        except BaseException:
            self.close()
            raise

    def close(self):
        if self._closed:
            return
        self._closed = True
        failures = []
        try:
            if self.process and self.process.poll() is None:
                try:
                    self.adb("emu", "kill", check=False, timeout=10)
                except (subprocess.TimeoutExpired, OSError):
                    pass
                try:
                    self.process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        self.process.kill()
                        self.process.wait(timeout=10)
            self.cleanup["emulatorExited"] = self.process is None or self.process.poll() is not None
        finally:
            try:
                if self.adb_started:
                    try:
                        subprocess.run([str(sdk_binary(self.sdk, "platform-tools", "adb")), "-P", str(self.ports.adb), "kill-server"],
                            env=self.environment, capture_output=True, timeout=10)
                    except (subprocess.TimeoutExpired, OSError):
                        pass
                    closed = all(_port_closed(port) for port in self.ports.all)
                    self.cleanup["privatePortsClosed"] = closed
                    if not closed:
                        failures.append("Owned emulator/ADB ports did not close; enclosing guard must recover")
            finally:
                if self.log:
                    self.log.close()
                if self.lock:
                    self.lock.close()
                    (self.root / ".emulator-session.lock").unlink(missing_ok=True)
        if failures:
            raise RuntimeError("; ".join(failures))

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()
        return False
