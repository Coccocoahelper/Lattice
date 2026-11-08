#!/bin/sh
# Keep the server up through WindSpigot's world-unload crash: restart it when it exits, with a short backoff.
# Usage: scripts/keepalive.sh  (runs forever; logs to run/server.log and run/keepalive.log)
cd "$(dirname "$0")/../run" || exit 1
while true; do
    [ -p cmd.fifo ] || { rm -f cmd.fifo; mkfifo cmd.fifo; }
    # hold the fifo open so the server's stdin never EOFs
    (sleep 100000 > cmd.fifo) &
    HOLDER=$!
    echo "[keepalive] $(date -u +%H:%M:%S) starting server" >> keepalive.log
    java -Xms640M -Xmx1700M -XX:+UseG1GC -jar "$PWD/WindSpigot.jar" nogui > server.log 2>&1 < cmd.fifo
    CODE=$?
    kill "$HOLDER" 2>/dev/null
    echo "[keepalive] $(date -u +%H:%M:%S) server exited ($CODE), restarting in 15s" >> keepalive.log
    sleep 15
done
