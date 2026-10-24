# M1.1 artifact ticket contract

This package freezes the source-only lifecycle for user-selected image/file
attachments: digest before ticket, proof-of-possession bound to that ticket,
bounded message commit, interrupted-upload replacement, and 24-hour orphan
reclamation. It accepts only Photo Picker/SAF selection identities; arbitrary
paths, URLs and provider URIs are rejected at this boundary.

The contract does not open files, upload bytes, run an object store, scan MIME
content, or claim a production artifact service. A production implementation
still needs the locked picker/object-store/scanner dependency, ticket service,
durable message commit and Android/device evidence. `localCopyDeletionAllowed`
is a receipt fact only; a caller must perform deletion after its own durable
commit transaction.

Limits are four files per message, 25 MiB per non-audio file, 10 MiB per
`AUDIO_MP4` AAC/M4A (`audio/mp4`) file, 120 seconds per audio file, 50 MiB total and 24 hours for
uncommitted orphan reclamation. Supported media types are JPEG, PNG, WebP, PDF,
plain text and AAC/M4A audio. A committed ticket receipt exposes an opaque
`artifactId` equal to the Bridge-issued ticket ID; it is absent before commit.

## Local verification

```sh
./.worktrees/p0a-protocol-security-model/node_modules/.bin/vitest \
  --root "$PWD" run artifact-contract/test/artifact-ticket.test.ts
./.worktrees/p0a-protocol-security-model/node_modules/.bin/tsc \
  --noEmit --target ES2022 --module NodeNext --moduleResolution NodeNext \
  --strict --skipLibCheck artifact-contract/src/*.ts
```
