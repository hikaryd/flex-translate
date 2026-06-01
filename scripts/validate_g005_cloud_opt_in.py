#!/usr/bin/env python3
from pathlib import Path
import json
import re

required = [
    'configs/cloud-providers.json',
    'docs/security/cloud-opt-in.md',
    'apps/android/app/src/main/java/dev/flextranslate/foundation/CloudOptIn.kt',
    'apps/ios/FlexTranslate/Sources/FlexTranslate/CloudOptIn.swift',
]
missing = [p for p in required if not Path(p).is_file()]
if missing:
    raise SystemExit(f'missing: {missing}')

cfg = json.loads(Path('configs/cloud-providers.json').read_text())
assert cfg['policy']['cloudOptInOnly'] is True
assert cfg['policy']['noEmbeddedApiKeys'] is True
assert cfg['policy']['credentialMode'] == 'backend_ephemeral_token_or_backend_mediation'
roles = {p['role'] for p in cfg['providers']}
assert roles == {'recognition_fallback', 'realtime_assistant_conversation', 'batch_chunked_enrichment'}
for provider in cfg['providers']:
    assert provider['requiresConsent'] is True
    assert provider['requiresEphemeralCredential'] is True
    assert provider['offlineDependencyAllowed'] is False

combined = '\n'.join(Path(p).read_text(errors='ignore') for p in required)
for token in ['backend_ephemeral_token', 'userConsented', 'disclosureAccepted', 'offline', 'local ASR']:
    assert token in combined, token

# Reject common embedded key shapes in tracked source/config/docs, allowing prose mention of API keys.
secret_patterns = [
    re.compile(r'AIza[0-9A-Za-z_\-]{20,}'),
    re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
]
scan_roots = ['apps', 'configs', 'docs', 'schemas', 'benchmarks', 'scripts']
violations = []
for root in scan_roots:
    for path in Path(root).rglob('*'):
        if path.is_file():
            text = path.read_text(errors='ignore')
            for pattern in secret_patterns:
                if pattern.search(text):
                    violations.append(str(path))
if violations:
    raise SystemExit(f'embedded secret pattern found: {violations}')

print('G005 cloud opt-in validation: PASS')
print(f'provider_count: {len(cfg["providers"])}')
