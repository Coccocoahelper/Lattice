#!/bin/sh
# Send a console command to the server through the stdin fifo (RCON has been unreliable).
cd "$(dirname "$0")/../run" || exit 1
echo "$*" > cmd.fifo
