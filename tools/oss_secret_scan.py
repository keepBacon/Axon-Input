#!/usr/bin/env python3
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {'.git', 'out', 'build', '.gradle', 'node_modules', '.idea'}
BINARY_EXTS = {'.png','.jpg','.jpeg','.webp','.gif','.ttf','.otf','.moc3','.wasm','.so','.jar','.dex','.apk','.aab','.zip','.7z','.pdf'}

PATTERNS = [
    ('private-key', re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----')),
    ('github-token', re.compile(r'\b(?:ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b')),
    ('gitlab-token', re.compile(r'\bglpat-[A-Za-z0-9_-]{16,}\b')),
    ('openai-like-secret', re.compile(r'\bsk-[A-Za-z0-9_-]{20,}\b')),
    ('google-api-key', re.compile(r'\bAIza[0-9A-Za-z_-]{20,}\b')),
    ('aws-access-key', re.compile(r'\bAKIA[0-9A-Z]{16}\b')),
    ('tencent-secret-id', re.compile(r'\bAKID[A-Za-z0-9]{12,}\b')),
    ('supabase-secret', re.compile(r'\bsb_secret_[A-Za-z0-9._-]{10,}\b')),
    ('jwt', re.compile(r'\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b')),
    ('supabase-project-ref', re.compile(r'--project-ref\s+(?!<)[a-z0-9]{10,}\b')),
    ('hardcoded-service-secret', re.compile(r'(?i)(?:SERVICE_ROLE|SECRET_KEY|DATABASE_PASSWORD|KEYSTORE_PASS)\s*[:=]\s*["\'](?![.<$])[^"\']{8,}["\']')),
]

forbidden_files = {
    Path('cloud-control/security.json'),
}
credential_exts = {'.jks','.keystore','.p12','.pfx','.pem','.key'}
findings=[]

for rel in forbidden_files:
    if (ROOT / rel).exists():
        findings.append((str(rel), 0, 'forbidden-sensitive-file'))

for p in ROOT.rglob('*'):
    if not p.is_file():
        continue
    rel=p.relative_to(ROOT)
    if any(part in SKIP_DIRS for part in rel.parts):
        continue
    if p.suffix.lower() in credential_exts:
        findings.append((str(rel), 0, 'credential-file'))
        continue
    if p.suffix.lower() in BINARY_EXTS:
        continue
    try:
        text=p.read_text(encoding='utf-8', errors='ignore')
    except Exception:
        continue
    for lineno,line in enumerate(text.splitlines(),1):
        if '<your-' in line or '<GENERATE_WITH_' in line or 'sb_publishable_...' in line or "='...'" in line:
            continue
        for name,rx in PATTERNS:
            if rx.search(line):
                findings.append((str(rel), lineno, name))

if findings:
    print('Open-source secret scan FAILED:')
    for path,line,name in findings:
        suffix=f':{line}' if line else ''
        print(f'  {path}{suffix}: {name}')
    sys.exit(1)

print('Open-source secret scan: OK')
