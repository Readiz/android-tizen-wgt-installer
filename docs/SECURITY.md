# Security boundaries

Experimental software. No claim of a security audit or end-to-end TV validation.

- App does not collect Samsung passwords; sign-in is in the system browser.
- Callback uses a random 256-bit state and listens only on loopback. Samsung tokens
  are memory-only and are excluded from logging, state, receipts and GitHub requests.
- Private TV signing keys are exportable (the signer needs them), wrapped at rest
  with a non-exportable Android Keystore AES-GCM key. No private key is sent to TV.
- Android backups are disabled. Recovery/export/import is not implemented. Do not
  clear app data or uninstall when these keys are needed to update existing apps.
- Cloud transport uses TLS and an explicit host allowlist. Certificate issuance POST
  redirects are denied. Local HTTP is limited to the selected private TV at port 8001.
- SDB is not encrypted: use trusted Wi-Fi. The WGT, public certificates, device profile
  and commands are observable to a network adversary. Source IP is not strong auth.
- Production file upload API permits only two fixed package/profile staging paths.
- No arbitrary-shell UI, root request, uninstall command, privilege patch or auth bypass.
- Manifest identifiers and paths are validated; external XML entities and DTDs denied;
  ZIP inflation bounded; downloaded bytes checked against pinned SHA-256 and size.
- User-picked local WGT files are a separate, explicitly trusted input. Private
  cached bytes are pinned and rechecked by size/hash before signing; this hash
  provides continuity, not publisher verification. Local/GitHub source changes
  cannot automatically replace an existing app receipt.
- No absent GitHub digest fallback, auto first-file choice, or automatic replay after
  a possibly successful installation. Trust is NOT inferred from SHA-256 alone.
- Demo self-signed keys are generated in memory and never stored in the Android vault.
  Demo endpoints cannot reach a real TV; UI/logs explicitly say simulation.
- Starting/stopping foreground services, notification permission and Keystore behavior
  remain Android device/emulator test gates, not JVM-verified properties.

Known limitations: no encrypted backup UI, no certificate renewal flow, no malware
inspection, no remote repository attestation, in-memory ZIP/signing can use substantial
RAM, and no physical-TV adversarial tests.
