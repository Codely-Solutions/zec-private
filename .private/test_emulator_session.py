"""Configuration/integrity tests only: no emulator, ADB server or download is started."""
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

from emulator_session import (EmulatorSession, IMAGE_MARKER, IMAGE_SHA1, IMAGE_SIZE,
                              load_manifest, prepare_image, resolve_slot, session_ports, verify_image)


class EmulatorSessionTest(unittest.TestCase):
    def test_default_and_second_slot_have_disjoint_ports(self):
        first, second = session_ports(environ={}), session_ports(environ={"CODEX_EMULATOR_SLOT": "1"})
        self.assertEqual((5041, 5580, 5581, 8554, 8877), first.all)
        self.assertEqual((5042, 5582, 5583, 8556, 8878), second.all)
        self.assertEqual("emulator-5580", first.serial)
        self.assertEqual("emulator-5582", second.serial)
        self.assertFalse(set(first.all) & set(second.all))

    def test_slot_bounds_reject_extra_workers(self):
        for value in (-1, 2, "2", "01", True, "", "1.0"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                resolve_slot(value, {})
        self.assertEqual(0, resolve_slot(0, {"CODEX_EMULATOR_SLOT": "1"}))

    def test_manifest_default_and_absolute_override_are_independent(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            (root / "tor-probe-artifact.json").write_text('{"id":1}')
            second = root / "worker-manifest.json"
            second.write_text('{"id":2}')
            self.assertEqual({"id": 1}, load_manifest(root / "source", {}))
            self.assertEqual({"id": 2}, load_manifest(root / "source",
                {"CODEX_EMULATOR_MANIFEST": str(second)}))
            with self.assertRaises(ValueError):
                load_manifest(root, {"CODEX_EMULATOR_MANIFEST": "relative.json"})
            second.write_text("[]")
            with self.assertRaises(ValueError):
                load_manifest(root, {"CODEX_EMULATOR_MANIFEST": str(second)})

    def test_sessions_use_only_private_adb_and_worker_state(self):
        with tempfile.TemporaryDirectory() as name, patch("emulator_session.subprocess.Popen") as launch:
            root = Path(name)
            env = {"CODEX_GUARD_OUTPUT": str(root), "UNRELATED": "preserved"}
            first = EmulatorSession(root / "worker0", environ=env)
            second = EmulatorSession(root / "worker1", slot=1, guest_memory=3072, environ=env)
            self.assertEqual(["-P", "5041", "-s", "emulator-5580"], first.adb.command_base[1:])
            self.assertEqual(["-P", "5042", "-s", "emulator-5582"], second.adb.command_base[1:])
            self.assertEqual("tcp:localhost:5042", second.adb.environment["ADB_SERVER_SOCKET"])
            self.assertEqual("5042", second.adb.environment["ANDROID_ADB_SERVER_PORT"])
            self.assertEqual("preserved", second.adb.environment["UNRELATED"])
            for key in ("ANDROID_AVD_HOME", "ANDROID_USER_HOME", "ANDROID_EMULATOR_HOME", "TEMP", "TMP"):
                self.assertNotEqual(first.environment[key], second.environment[key])
                self.assertTrue(Path(second.environment[key]).is_relative_to(second.root))
            command = second.command()
            self.assertEqual("8556", command[command.index("-grpc") + 1])
            self.assertEqual(8877, first.adb.bridge_port)
            self.assertEqual(8878, second.adb.bridge_port)
            self.assertEqual("5582", command[command.index("-port") + 1])
            self.assertNotIn("-writable-system", command)
            self.assertNotEqual(first.avd_name, second.avd_name)
            self.assertEqual("CodexTorProbe", first.avd_name)
            launch.assert_not_called()
            self.assertFalse(first.root.exists())
            with patch("emulator_session.subprocess.run") as run:
                second.adb("shell", "true", timeout=7)
                run.assert_called_once_with([*second.adb.command_base, "shell", "true"],
                    env=second.environment, capture_output=True, timeout=7, check=True)

    def test_guest_memory_and_guard_root_are_bounded(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            env = {"CODEX_GUARD_OUTPUT": str(root)}
            for memory in (True, 0, 511, 3073, "1536"):
                with self.subTest(memory=memory), self.assertRaises(ValueError):
                    EmulatorSession(root / "worker", guest_memory=memory, environ=env)
            with self.assertRaises(ValueError):
                EmulatorSession(root.parent / "outside-worker", environ=env)
            with self.assertRaises(ValueError):
                EmulatorSession(root, environ={})

    def _image(self, root):
        system = root / "verified-image"
        system.mkdir()
        payload = system / "system.img"
        payload.write_bytes(b"small test image, never booted")
        digest = hashlib.sha256(payload.read_bytes()).hexdigest()
        (system / IMAGE_MARKER).write_text(json.dumps({"archiveSha1": IMAGE_SHA1,
            "archiveBytes": IMAGE_SIZE, "files": {"system.img": digest}}))
        return system, payload

    def test_shared_verified_image_never_downloads_or_extracts(self):
        with tempfile.TemporaryDirectory() as name:
            system, _ = self._image(Path(name))
            with patch("emulator_session.urllib.request.urlopen") as download:
                resolved = prepare_image(Path(name) / "worker", {"CODEX_EMULATOR_IMAGE": str(system)})
                self.assertEqual(system.resolve(), resolved)
                download.assert_not_called()
            self.assertFalse((Path(name) / "worker").exists())

    def test_shared_image_tampering_extra_files_and_relative_override_fail(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            system, payload = self._image(root)
            self.assertEqual(system.resolve(), verify_image(system))
            extra = system / "unverified.img"
            extra.write_bytes(b"not in verification marker")
            with self.assertRaises(ValueError):
                verify_image(system)
            extra.unlink()
            payload.write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                verify_image(system)
            with self.assertRaises(ValueError):
                prepare_image(root, {"CODEX_EMULATOR_IMAGE": "relative-image"})

    def test_cleanup_before_start_is_idempotent_and_never_touches_adb(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            session = EmulatorSession(root / "worker", environ={"CODEX_GUARD_OUTPUT": str(root)})
            with patch("emulator_session.subprocess.run") as command:
                session.close()
                session.close()
                command.assert_not_called()
            with self.assertRaises(RuntimeError):
                session.__enter__()


if __name__ == "__main__":
    unittest.main()
