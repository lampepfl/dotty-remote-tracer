# Dotty remote trace

A Play application that receives LSP logs coming from Dotty VSCode extension.

So far it is only meant to be used as part of EPFL courses.

## VSCode configuration

Example:

```json
{
  "dotty.remoteWorkspaceDumpUrl": "http://localhost:7000/upload/workspace-dump.zip",
  "dotty.remoteTracingUrl": "ws://localhost:7000/upload/lsp.log",
  "dotty.trace.server": { "format": "JSON", "verbosity": "verbose" }
}
```

Both URLs are independent and can be omitted - their respective tracing will be
omitted if they are missing.

Last line is necessary to enable VSCode LSP tracing. If both URLs are omitted,
LSP will be simply logged to local output.
