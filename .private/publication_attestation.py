"""Recognize the original and indexed release attestation artifact names."""
import re


ATTESTATION_ARTIFACT = re.compile(
    r'release-attestation-([1-9][0-9]*)(?:-([1-9][0-9]*)-(v[0-9]+\.[0-9]+\.[0-9]+-private\.[1-9][0-9]*))?')
