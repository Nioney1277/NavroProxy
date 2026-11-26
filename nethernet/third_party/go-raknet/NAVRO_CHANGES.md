# Navro compatibility changes

Source: github.com/sandertv/go-raknet v1.15.2, retaining its original LICENSE.

The local module adds optional `ListenConfig.ProtocolVersions` and
`Dialer.ProtocolVersion` for Bedrock RakNet revisions 9, 10 and 11. Defaults
remain upstream's revision 11. Navro's RakNet-to-NetherNet listener permits
the legacy revisions and disables cookies for compatibility with old clients.
The adapter still authenticates game login signatures and completes the
Bedrock encryption handshake before forwarding gameplay.

The NetherNet-to-RakNet route reads the client's first protocol declaration
before dialing and preserves that first batch for the Java adapter.
