#!/usr/bin/env python3
import hashlib, os, sys

if len(sys.argv) < 2:
    raise SystemExit("usage: generate_entry_password.py <password> [iterations]")
password = sys.argv[1].encode("utf-8")
iterations = int(sys.argv[2]) if len(sys.argv) > 2 else 210000
if iterations < 100000 or iterations > 2000000:
    raise SystemExit("iterations must be between 100000 and 2000000")
salt = os.urandom(16)
digest = hashlib.pbkdf2_hmac("sha256", password, salt, iterations, dklen=32)
print('{')
print('  "algorithm": "pbkdf2-sha256",')
print(f'  "salt": "{salt.hex()}",')
print(f'  "iterations": {iterations},')
print(f'  "hash": "{digest.hex()}"')
print('}')
