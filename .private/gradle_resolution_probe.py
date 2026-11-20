"""Exercise dependency recovery with the pinned Gradle and a loopback Maven repository."""

import argparse
from collections import Counter
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import io
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
from threading import Thread
import time
from urllib.request import ProxyHandler, build_opener
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from gradle_resolution import run_with_recovery


ROOT = Path(__file__).resolve().parents[1]
PARENT = ("org.gradle.kotlin", "gradle-kotlin-dsl-plugins", "6.7.13")
CHILD = ("org.jetbrains.kotlin", "kotlin-gradle-plugin-api", "2.4.21")
WRAPPER = ROOT / "gradle/wrapper/gradle-wrapper.properties"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def gradle_installation():
    properties = dict(line.split("=", 1) for line in WRAPPER.read_text().splitlines() if "=" in line)
    archive = properties["distributionUrl"].rsplit("/", 1)[-1]
    match = re.fullmatch(r"(gradle-[0-9.]+)-(?:all|bin)\.zip", archive)
    if not match:
        raise RuntimeError(f"Unsupported wrapper distribution name: {archive}")
    distribution = match[1]
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    launchers = sorted((cache / "wrapper/dists" / archive.removesuffix(".zip")).glob(
        f"*/{distribution}/bin/gradle"
    ))
    if not launchers:
        raise RuntimeError(f"Install {archive} through the project wrapper before running the probe")
    return launchers[0].resolve(), distribution, properties["distributionSha256Sum"]


def repository_path(coordinate, extension):
    group, name, version = coordinate
    return f"/{group.replace('.', '/')}/{name}/{version}/{name}-{version}.{extension}"


def dependency_elements(coordinate):
    return "".join(
        f"<{tag}>{value}</{tag}>"
        for tag, value in zip(("groupId", "artifactId", "version"), coordinate)
    )


def synthetic_artifacts():
    jar = io.BytesIO()
    with ZipFile(jar, "w"):
        pass
    files = {}
    for coordinate in (PARENT, CHILD):
        dependencies = ""
        if coordinate == PARENT:
            dependencies = f"<dependencies><dependency>{dependency_elements(CHILD)}</dependency></dependencies>"
        pom = f"<project><modelVersion>4.0.0</modelVersion>{dependency_elements(coordinate)}{dependencies}</project>"
        files[repository_path(coordinate, "pom")] = pom.encode()
        files[repository_path(coordinate, "jar")] = jar.getvalue()
    return files


def write_metadata(path, files, *, invalid_child_jar=False):
    root = ET.Element("verification-metadata", xmlns="https://schema.gradle.org/dependency-verification")
    options = ET.SubElement(root, "configuration")
    ET.SubElement(options, "verify-metadata").text = "true"
    ET.SubElement(options, "verify-signatures").text = "false"
    components = ET.SubElement(root, "components")
    for group, name, version in (PARENT, CHILD):
        component = ET.SubElement(components, "component", group=group, name=name, version=version)
        for extension in ("pom", "jar"):
            artifact = ET.SubElement(component, "artifact", name=f"{name}-{version}.{extension}")
            digest = sha256(files[repository_path((group, name, version), extension)])
            bad_jar = invalid_child_jar and (group, name, version) == CHILD and extension == "jar"
            ET.SubElement(artifact, "sha256", value="0" * 64 if bad_jar else digest)
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def exercise(work, out, result):
    launcher, distribution, archive_hash = gradle_installation()
    result["gradle"] = {"distribution": distribution, "wrapper_archive_sha256": archive_hash,
                        "launcher_sha256": sha256(launcher.read_bytes())}
    files = synthetic_artifacts()
    child_pom = repository_path(CHILD, "pom")
    child_jar = repository_path(CHILD, "jar")
    visible = {path: content for path, content in files.items() if path not in (child_pom, child_jar)}
    requests = Counter()

    class Repository(BaseHTTPRequestHandler):
        def setup(self):
            self.request.settimeout(2)
            super().setup()

        def do_GET(self):
            self.serve_artifact()

        def do_HEAD(self):
            self.serve_artifact()

        def serve_artifact(self):
            content = visible.get(self.path)
            status = 200 if content is not None else 404
            requests[(self.command, self.path, status)] += 1
            self.send_response(status)
            self.send_header("Content-Length", str(len(content or b"")))
            self.end_headers()
            if content is not None and self.command == "GET":
                self.wfile.write(content)

        def log_message(self, *_):
            pass

    commands = result["commands"] = []
    probes = result["pom_probes"] = []
    deadline = time.monotonic() + 235

    def runner(command):
        started = time.monotonic()
        output = ""
        record = {"argv": list(command)}
        commands.append(record)
        try:
            with subprocess.Popen(command, cwd=work, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True, errors="replace",
                                  start_new_session=True) as process:
                record["pid"] = process.pid
                try:
                    output, _ = process.communicate(timeout=min(60, max(1, deadline - started)))
                    return process.returncode, output
                finally:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait(timeout=5)
                    record["exit_code"] = process.returncode
        except subprocess.TimeoutExpired as error:
            output = (error.output or b"").decode(errors="replace")
            raise RuntimeError("Gradle configuration timed out") from None
        finally:
            record["seconds"] = round(time.monotonic() - started, 3)
            (out / f"gradle-{len(commands)}.log").write_text(output[-16000:], encoding="utf-8")

    with HTTPServer(("127.0.0.1", 0), Repository) as server:
        worker = Thread(target=server.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True)
        worker.start()
        try:
            endpoint = f"http://127.0.0.1:{server.server_port}"
            (work / "settings.gradle").write_text("rootProject.name = 'resolution-probe'\n", encoding="utf-8")
            (work / "build.gradle").write_text(f"""
buildscript {{
    repositories {{
        maven {{
            url = uri('{endpoint}')
            allowInsecureProtocol = true
            metadataSources {{ mavenPom(); ignoreGradleMetadataRedirection() }}
        }}
    }}
    dependencies {{ classpath '{':'.join(PARENT)}' }}
}}
""", encoding="utf-8")
            metadata = work / "gradle/verification-metadata.xml"
            metadata.parent.mkdir()
            write_metadata(metadata, files)
            command = [str(launcher), "--gradle-user-home", str(work / "home"), "--no-daemon",
                       "--no-watch-fs", "--max-workers=2", "--console=plain",
                       "-Dorg.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m",
                       "--dependency-verification", "strict", "help"]
            status, output = runner(command)
            if not status or f"Could not find {':'.join(CHILD)}" not in output:
                raise RuntimeError("The initial run did not fail for the expected missing Kotlin POM")
            if not requests[("GET", child_pom, 404)]:
                raise RuntimeError("The repository did not report the expected missing POM")
            visible.update(files)

            def verify(coordinate, pin, timeout):
                probes.append(":".join(coordinate))
                extension, digests = pin
                opener = build_opener(ProxyHandler({}))
                with opener.open(endpoint + repository_path(coordinate, extension), timeout=timeout) as response:
                    return sha256(response.read(65536)) in digests

            warm_fetches = []

            def warm_runner(argv):
                before = sum(requests[(method, child_pom, 200)] for method in ("GET", "HEAD"))
                status, output = runner(argv)
                after = sum(requests[(method, child_pom, 200)] for method in ("GET", "HEAD"))
                warm_fetches.append(after - before)
                return status, output

            if run_with_recovery(command, metadata=metadata, runner=warm_runner, verify=verify):
                raise RuntimeError("The published Kotlin artifact could not be resolved")
            warm, *retries = commands[1:]
            if warm["exit_code"]:
                if len(retries) != 1 or "--refresh-dependencies" not in retries[0]["argv"] or probes != [
                    ":".join(CHILD)
                ]:
                    raise RuntimeError("The Kotlin classpath failure did not use one verified refresh")
                result["warm_result"] = ("cached-miss-recovered" if not warm_fetches[0]
                                         else "refetched-then-recovered")
            else:
                if retries or probes or not warm_fetches[0]:
                    raise RuntimeError("Warm resolution succeeded without the expected repository fetch")
                result["warm_result"] = "gradle-rechecked-missing-metadata"

            write_metadata(metadata, files, invalid_child_jar=True)
            expected_commands, expected_probes = len(commands) + 1, len(probes)
            if not run_with_recovery(command, metadata=metadata, runner=runner, verify=verify):
                raise RuntimeError("Gradle accepted an invalid Kotlin JAR checksum")
            last_log = (out / f"gradle-{len(commands)}.log").read_text(encoding="utf-8")
            if ("Dependency verification failed" not in last_log or CHILD[1] not in last_log
                    or len(commands) != expected_commands or len(probes) != expected_probes):
                raise RuntimeError("The checksum failure did not reach Gradle verification without a retry")
            result["checksum_result"] = "rejected-without-refresh"
        finally:
            server.shutdown()
            worker.join(timeout=5)
            result["server_stopped"] = not worker.is_alive()
            result["http_requests"] = [
                {"method": method, "path": path, "status": status, "count": count}
                for (method, path, status), count in sorted(requests.items())
            ]
            if worker.is_alive():
                raise RuntimeError("The loopback repository did not stop")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    out = parser.parse_args().out.resolve()
    out.mkdir()
    started = time.monotonic()
    result = {"source": os.environ.get("GITHUB_SHA"), "probe_sha256": sha256(Path(__file__).read_bytes())}
    try:
        with tempfile.TemporaryDirectory(prefix="fixture-", dir=out) as work:
            exercise(Path(work), out, result)
        result["passed"] = True
    except Exception as error:
        result.update(passed=False, error=str(error))
    finally:
        result["seconds"] = round(time.monotonic() - started, 3)
        (out / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(result.get("error", result.get("warm_result")))
    return 0 if result.get("passed") else 1


if __name__ == "__main__":
    raise SystemExit(main())
