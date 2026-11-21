#!/usr/bin/env python3
"""Reapply one explicitly configured OP after spawn on a private offline BDS.

The backend must be reachable only through the authenticated frontend. BDS
discards XUIDs for offline logins, so its permissions.json cannot retain OP.
This helper never changes the default permission level or proxy exemptions.
"""
import argparse
import json
import os
import re
import stat
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--console", required=True, type=Path)
    parser.add_argument("--player", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_ ]{1,32}", args.player):
        parser.error("player must be an exact Bedrock gamertag")
    if not stat.S_ISFIFO(args.console.stat().st_mode):
        parser.error("console must be the private BDS command FIFO")
    marker = " INFO] Player Spawned: " + args.player + " xuid:"
    command = ('op @a[name="' + args.player + '"]\n').encode()
    with args.log.open() as stream:
        stream.seek(0, os.SEEK_END)
        print(json.dumps({"event": "operator_watch_ready", "player": args.player}), flush=True)
        while True:
            line = stream.readline()
            if not line:
                time.sleep(0.25)
                continue
            if marker not in line:
                continue
            fd = os.open(args.console, os.O_WRONLY | os.O_NONBLOCK)
            try:
                os.write(fd, command)
            finally:
                os.close(fd)
            print(json.dumps({"event": "operator_command_sent", "player": args.player}), flush=True)


if __name__ == "__main__":
    main()
