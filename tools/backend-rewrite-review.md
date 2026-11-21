# Prior BDS-session production audit

| File | Result |
|---|---|
| SessionState.java | Rewritten: explicit backend policy; immutable correction capture; native immediate ACK versus BDS deferred ACK; BDS-only Respawn coordinates. |
| ProxySession.java | Rewritten: configured backend creates state; only BDS installs correction-batch handler. |
| NetherNetBatchSession.java | Rewritten: default NAVRO; explicit BDS constructor. Removed unconditional BDS spawn and sprint enablement. Command advertisement retained and independently tested. |
| PlayerVelocityPackets.java | Rewritten: concrete packet identity replaces weak maps. Correction cutoffs are BDS-only; Navro uses original single ACK/single consumption. |
| PredictionRewind.java | Rewritten against original TeleportHandler: source baseline, contiguous history, sequential prediction, historical acceptance, live restoration in finally. Source tick must exist; missing history uses authoritative hard reset. |
| WirePlayerState.java | Rewritten replay restoration: copies input declarations only, without rolling back collision state, server observations or metadata ACK queues. |
| RewindData.java | Rewritten: immutable motion cutoff and historical Y-offset fallback, preserving actual AABB. |
| TeleportUtil.java | Reviewed/revised: ordered drain retains correction until an input after source tick; hard/soft forwarding remains original Navro behavior. |
| TickData.java | Reviewed/retained: no historical external-velocity regeneration; copied pre-rewrite packet and movement/pose snapshots. |
| NetworkLatencyPackets.java | Reviewed/retained: timestamp and PlayStation scaling extraction is equivalent to original; no generic ACK deferral. |
| PredictionRunner.java | Reviewed/retained: prior modification is motion-consumption diagnostics only; it does not alter candidate selection. |
| WorldCollisionDiagnostics.java | Reviewed/retained: raw packet tracing and bounded input history. No tolerance or exemption changes. |
| SupportedCodecs.java | Reviewed/retained: nullable chained-command serializer registration; original command schema verified on recorded BDS capture and supported protocols. |
| LosslessAvailableCommandsSerializer.java | Reviewed/retained: absent chained values encode as -1, outside string tables; serializer fix applies to protocol data for any backend. |
| NavroAlertCommand.java | Reviewed/retained: optional root argument, root usage, permission-checked alert toggle, namespaced root. No prediction logic. |
| PredictionCorrectionAcknowledgments.java | Removed from common utilities; replacement is explicit bds/BdsCorrectionAcknowledgments and cannot be installed on NAVRO state. |
