# MobileCore spike

`mobilecore` is an independent Go module and the only Go/Kotlin binding surface.
Kotlin sends versioned JSON commands to `Core.Dispatch` and receives versioned
JSON state from both the return value and `Platform.OnState`.

Pinned dependencies:

- `github.com/FireflyTang/codex-remote-protocol v1.1.3-0.20260904143425-724b0b2543b0`
  (wire `1.2.0`, schema commit
  `724b0b2543b0e28a039576eff81312d8b75278f9`, WebSocket subprotocol
  `codex-remote.v1.protojson`). The immutable pseudo-version pins the corrected
  schema independently of the previously published `v1.2.0` tag and proxy
  caches; it is based on the commit's `v1.1.2` ancestry while the wire protocol
  remains exactly `1.2.0`.
- `tailscale.com v1.102.3`
- AAR tool: `golang/mobile` commit
  `4776eadac327bcb80cebc7413c91f8b4abf8ffa1`, with `x/tools v0.49.0`,
  `x/mod v0.39.0`, and `x/sync v0.22.0`.

The first milestone implements app-private `tsnet` userspace networking, the
Host WebSocket handshake, discovery, and the basic conversation flow. It does
not use Android `VpnService` or a system `tailscaled`.

## Android platform seam

Before `start`, Kotlin must implement `Platform`:

- `InterfacesJSON()` returns the current `java.net.NetworkInterface` set using
  the JSON shape exercised in `interfaces_test.go`. Go registers it with
  Tailscale's `netmon.RegisterInterfaceGetter`, avoiding Android's restricted
  `net.Interfaces`/netlink path.
- `BindSocketToNetwork(fd)` accepts the socket without binding it to a specific
  Android `Network`. The socket follows Android's default route, allowing Clash
  to apply its configured split-routing rules instead of bypassing or binding
  directly to the VPN or its physical underlay.
- `OnState(json)` forwards immutable state snapshots to Kotlin.

Kotlin selects a usable physical `NOT_VPN` network only for netmon metadata and
sends `network_changed` whenever that metadata changes. This selection does not
bind traffic to the physical network:

```json
{"version":1,"type":"network_changed","payload":{"defaultInterface":"wlan0","defaultGateway":"192.168.1.1"}}
```

## Minimal command flow

```json
{"version":1,"id":"configure-1","type":"configure","payload":{"hostname":"codex-remote-android","stateDir":"/data/user/0/APP/files/tailnet","hostEndpoint":"codex-remote-linux","clientId":"stable-install-id","clientRunId":"process-run-id"}}
{"version":1,"id":"start-1","type":"start"}
{"version":1,"id":"refresh-1","type":"refresh"}
{"version":1,"id":"select-1","type":"select_codex","payload":{"codexId":"CODEX-1"}}
{"version":1,"id":"turn-1","type":"start_turn","payload":{"text":"Hello","options":{"model":"gpt-5","mode":"default","approvalPolicy":"never","reasoningEffort":"medium"}}}
{"version":1,"id":"conversation-1","type":"refresh_conversation","payload":{}}
{"version":1,"id":"interrupt-1","type":"interrupt_turn","payload":{"turnId":"TURN-1"}}
{"version":1,"id":"approve-1","type":"respond_approval","payload":{"approvalId":"APPROVAL-1","decision":"allow"}}
{"version":1,"id":"input-1","type":"respond_user_input","payload":{"requestId":"INPUT-1","answers":[{"questionId":"QUESTION-1","selectedOptionIds":["OPTION-1"],"freeFormText":""}]}}
{"version":1,"id":"workspace-1","type":"get_workspace","payload":{"codexId":"CODEX-1"}}
{"version":1,"id":"entries-1","type":"list_workspace_entries","payload":{"codexId":"CODEX-1","relativeDirectory":""}}
{"version":1,"id":"read-1","type":"read_workspace_text_file","payload":{"codexId":"CODEX-1","relativePath":"README.md"}}
{"version":1,"id":"write-1","type":"write_workspace_text_file","payload":{"codexId":"CODEX-1","relativePath":"README.md","utf8Text":"updated","condition":"replace_only","expectedRevision":"opaque-revision","expectedQuiescenceToken":"opaque-quiescence-token"}}
{"version":1,"id":"upload-1","type":"upload_workspace_entry","payload":{"codexId":"CODEX-1","destinationPath":"assets/logo.bin","kind":"regular_file","contentBase64":"YWJj","expectedQuiescenceToken":"opaque-quiescence-token"}}
{"version":1,"id":"download-1","type":"download_workspace_entry","payload":{"codexId":"CODEX-1","relativePath":"assets"}}
{"version":1,"id":"image-upload-stable-1","type":"upload_image_attachment","payload":{"codexId":"CODEX-1","filename":"photo.png","mimeType":"image/png","contentBase64":"aW1hZ2U=","sha256":"6105d6cc76af400325e94d588ce511be5bfdbb73b437dc51eca43917d7a43e3d"}}
{"version":1,"id":"image-download-1","type":"download_image_attachment","payload":{"codexId":"CODEX-1","attachmentId":"ATTACHMENT-1"}}
{"version":1,"id":"turn-mixed-1","type":"start_turn","payload":{"parts":[{"type":"text","text":"Describe "},{"type":"image","attachmentId":"ATTACHMENT-1"},{"type":"text","text":" precisely."}]}}
{"version":1,"id":"stop-1","type":"stop"}
```

`start` is asynchronous because interactive Tailscale login can block. State
progresses through `starting_tailnet`, optionally `auth_required`,
`connecting_host`, then `ready` or `error`. Auth keys are accepted in the
configure payload but never echoed in state.

`select_codex` and `refresh_conversation` asynchronously call `ListHistory`.
`start_turn` returns immediately, sends the RPC in the background, and polls
history about every 500 ms until its turn is terminal. `interrupt_turn` accepts
an omitted `turnId` when an active turn is known and continues polling after
the interrupt RPC. `stop` cancels all of these operations.

Conversation state is an app-owned DTO, not protobuf JSON. `items` is the
complete ordered projection of Host items; the legacy `messages` array remains
and is derived only from user and agent items in the same order:

```json
{"selectedCodexId":"CODEX-1","conversation":{"codexId":"CODEX-1","activeTurnId":"TURN-1","running":true,"historyComplete":true,"turns":[{"turnId":"TURN-1","status":"running","startedAtUnixMs":1,"completedAtUnixMs":0,"items":[{"itemId":"ITEM-1","type":"agent_message","status":"running","agentMessage":{"text":"Working"}}],"messages":[{"itemId":"ITEM-1","role":"assistant","text":"Working","status":"running"}]}]}}
```

Every item has `itemId`, optional `turnId`, `type`, and `status`, plus optional
`completeness` (`truncated`, `incomplete`, `originalSizeBytes`, `reason`). Known
types and their payloads are:

- `user_message`: ordered `userMessage.parts` (`text` or full immutable image
  descriptor), plus compatibility `textParts` and their newline-joined `text`
- `agent_message`: `agentMessage.text`
- `reasoning_summary`: `reasoningSummary.text`
- `plan`: `plan.steps[]` with `text` and protocol-defined string `status`
- `command`: `command.argv`, `cwd`, `output`, `hasExitCode`, and `exitCode` only
  when `hasExitCode` is true (including a present zero exit code)
- `tool`: `tool.name`, `summary`, and `resultSummary`
- `file_change`: `fileChange.changes[]` (`path`, `kind`, `oldPath`, `newPath`)
  and `unifiedDiff`; kind is `added`, `modified`, `deleted`, `renamed`, or
  `unspecified`
- `unknown`: no typed payload; used for nil or unrecognized content so item
  ordering remains lossless

History follows up to 10 pages (1000 turns); `historyComplete` is false if that
bound or the Host's own completeness boundary is reached.

The selected Codex also has one narrow `WatchCodex` stream for actionable
approvals and user-input requests. History and items still come from
`ListHistory`; every Watch event nevertheless advances `headEventSeq`. A gap
fails closed, clears pending requests, and rebuilds from a strict RESET. Codex
switches and `stop` cancel the old Watch and any in-flight response.

```json
{"conversation":{"pendingRequests":[{"type":"approval","requestId":"APPROVAL-1","turnId":"TURN-1","itemId":"ITEM-2","inFlight":false,"approval":{"kind":"command","status":"pending","title":"Run tests","explanation":"Permission required","command":["go","test","./..."],"allowedDecisions":["allow","allow_for_session","deny"]}},{"type":"user_input","requestId":"INPUT-1","turnId":"TURN-1","itemId":"ITEM-3","inFlight":false,"userInput":{"resolved":false,"questions":[{"questionId":"QUESTION-1","header":"Mode","prompt":"Choose a mode","options":[{"optionId":"OPTION-1","label":"Fast","description":"Use defaults"}],"allowsMultiple":false,"allowsFreeForm":false}]}}],"pendingWatch":{"state":"watching","headEventSeq":42}}}
```

Only currently pending requests are exposed. Starting a response sets
`inFlight=true`; a successful response or resolved event removes the request.
A failure restores it with `error={commandId,code,message}`. Approval decisions
must be listed in `allowedDecisions`. User-input answers must contain exactly
one entry per question, use valid option IDs, and obey the multiple/free-form
flags. Watch errors contain only `{code,message}` and use `watch_failed`,
`watch_gap`, or `invalid_event`.

## Workspace text flow

Workspace state is also an app-owned DTO. Call `get_workspace` first to obtain
the Host's five positive limits, workspace root, and access state. Omitting
`relativeDirectory` from `list_workspace_entries` means the root (`""`); the
Core follows every page until the Host returns an empty page token and rejects
repeated tokens.

```json
{"workspace":{"supported":true,"limits":{"maxTextFileBytes":1048576,"maxInlineUploadBytes":4194304,"maxInlineDownloadBytes":4194304,"maxArchiveExpandedBytes":67108864,"maxArchiveEntryCount":10000},"codexId":"CODEX-1","workspaceRoot":"/work","accessState":{"mutationStatus":"allowed","activeAgentCount":0,"quiescenceToken":"Q1","observedAtUnixMs":1,"generation":2},"currentDirectory":{"relativeDirectory":"","entries":[{"relativePath":"README.md","name":"README.md","kind":"regular_file","sizeBytes":7,"modifiedAtUnixMs":1,"revision":"R1","textViewable":true,"textEditable":true}]},"openFile":{"entry":{"relativePath":"README.md","name":"README.md","kind":"regular_file","sizeBytes":7,"modifiedAtUnixMs":1,"revision":"R1","textViewable":true,"textEditable":true},"utf8Text":"content"},"loading":"none"}}
```

`loading` is `none`, `workspace`, `entries`, `file`, or `write`. Failures are
reported as `workspace.error={code,message}` without disturbing conversation
state. Entry kinds are `unspecified`, `regular_file`, `directory`,
`symbolic_link`, or `other`. Writes require an `allowed` access state, the
current quiescence token, text within `maxTextFileBytes`, and one of
`create_only`, `replace_only`, or `upsert`; `replace_only` requires a revision
and `create_only` forbids one. A successful write updates both `lastWrite` and
`openFile` with the response entry and submitted text, then refreshes the
workspace access state because a committed mutation rotates its quiescence
token and advances its generation. If that refresh fails, the committed file
result remains visible, `accessState` is cleared to prevent reuse of the old
token, and `workspace.error` reports `operation_failed`; call `get_workspace`
before attempting another write.

Inline upload accepts canonical standard `contentBase64` and kind
`regular_file` or `zip_directory`; it requires the current quiescence token and
is limited by `maxInlineUploadBytes`. A committed upload is exposed as
`uploadResult={entry,deduplicated}` and then refreshes workspace access state;
as with text writes, refresh failure preserves the committed result, clears
`accessState`, and requires `get_workspace` before another mutation. Host ZIP
validation and expanded-size/entry-count errors are preserved as stable
lowercase workspace error codes.

Inline download accepts a canonical workspace-relative path (empty means the
workspace root) and returns
`downloadResult={entry,kind,filename,contentBase64}`, where kind is
`regular_file` or `zip_directory`. Decoded response content is limited by
`maxInlineDownloadBytes`. The two operations use `loading="upload"` or
`loading="download"` and do not replace `currentDirectory`, `openFile`, or
conversation state.

## Image attachments

`imageAttachments` exposes the v1.2 Host capability, upload limits and MIME
types, operation `loading`/`error`, and the latest upload or download result.
Uploads require canonical standard Base64, a supported lowercase `image/*`
MIME type, the raw-byte SHA-256 as 64 lowercase hex digits, and a stable command
ID. Retrying an uncertain upload must resend the identical command ID and
payload; the Core preserves that ID as the wire `request_id` across reconnect.
Downloads verify returned bytes against the descriptor size and SHA-256 before
publishing `contentBase64`.

Legacy `start_turn.payload.text` remains supported. New callers may instead
provide a non-empty ordered `parts` array containing `{type:"text",text}` and
`{type:"image",attachmentId}` entries. The two input forms are mutually
exclusive. History and realtime user-message items expose the same mixed order,
with image parts carrying the complete descriptor including optional paired
pixel dimensions.

## Verification

```sh
make test
make android-compile
ANDROID_HOME=/path/to/sdk make aar
```

The AAR target builds only `arm64-v8a` and `x86_64`, targets Android API 36,
and requires NDK `30.0.16138531` by default. `build-aar.sh` downloads and
builds the pinned gomobile tool under ignored `mobilecore/.tools/`; the small
patch in `patches/` prevents unrelated Tailscale tool dependencies from being
pulled into gomobile's temporary JNI module. The generated AAR remains under
ignored `mobilecore/build/` and is not copied into `app` automatically.
