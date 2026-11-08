# Automating the HomeTV server on Linux

This guide sets up two optional automations on a Linux server installed with
[`server-installation-linux.md`](server-installation-linux.md):

1. **HomeTV starts at boot**, even after the containers were stopped by hand, and only once the
   media disk is mounted.
2. **A daily power schedule**: the machine shuts down at **02:00** and powers on again at
   **15:00**.

Run every command on the server itself. The examples assume HomeTV lives in `~/hometv/deploy`
and the user is `lucas`. Replace `/home/lucas` with your own home directory where it appears.

## 1. Start HomeTV at boot

Both containers in `docker-compose.yml` have `restart: unless-stopped`, and Docker starts at boot,
so HomeTV usually comes back after a reboot on its own. It does not if someone ran
`docker compose down` or `docker compose stop` before the reboot. The systemd unit below closes that
gap: it runs `docker compose up -d` at every boot, after Docker and the media disk are ready.

### Check

```bash
systemctl is-enabled docker                    # should print "enabled"
cd ~/hometv/deploy && docker compose ps        # both containers "Up"
docker inspect -f '{{.Name}} {{.HostConfig.RestartPolicy.Name}}' $(docker compose ps -q)
grep MEDIA_DIR .env                            # where your media lives
findmnt --target "$(grep ^MEDIA_DIR .env | cut -d= -f2)"   # which disk/mount holds it
```

### Apply

Replace `/srv/media` in `RequiresMountsFor=` with your `MEDIA_DIR` and `/home/lucas` in
`WorkingDirectory=` with your home directory.

```bash
sudo systemctl enable --now docker

sudo tee /etc/systemd/system/hometv.service >/dev/null <<'EOF'
[Unit]
Description=HomeTV (docker compose)
Requires=docker.service
After=docker.service network-online.target
Wants=network-online.target
RequiresMountsFor=/srv/media

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/home/lucas/hometv/deploy
ExecStart=/usr/bin/docker compose up -d
ExecStop=/usr/bin/docker compose stop
TimeoutStartSec=0

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now hometv.service
systemctl status hometv.service
```

The unit never rebuilds the image. Updating HomeTV still means
`git pull && docker compose up -d --build`.

If the media is on an external or USB disk, it also needs an entry in `/etc/fstab` (by `UUID=`,
with the `nofail` option) so it mounts at boot. Check with `cat /etc/fstab` and `lsblk -f`.

## 2. Shut down at 02:00, power on at 15:00

Linux can shut the machine down, but it cannot switch a powered-off machine back on. The
motherboard's real-time clock (RTC) can: a script running at 02:00 sets an RTC alarm for 15:00,
then shuts the machine down cleanly.

### Check

```bash
timedatectl            # "Time zone" must be yours, e.g. Europe/Madrid; "RTC in local TZ: no"
# sudo timedatectl set-timezone Europe/Madrid   # if the time zone is wrong
ls /sys/class/rtc/rtc0/wakealarm && cat /proc/driver/rtc
```

Test the RTC wake once. This powers the machine off at once, and it should power back on by
itself after about 3 minutes:

```bash
sudo rtcwake -m off -s 180
```

If it stays off, enter the BIOS/UEFI setup (usually <kbd>Del</kbd> at boot) and look for
"Wake on RTC" / "RTC Power On" / "Resume by RTC Alarm": set it to *Enabled* or *By OS*. Turn
off "ErP" or deep-sleep power saving too, because it cuts power to the wake logic. If the OS
alarm never works on your board, set the BIOS RTC wake to a fixed daily time (15:00) instead
and keep only the shutdown timer below.

### Apply

The script sets the alarm for the next 15:00 and then shuts down:

```bash
sudo tee /usr/local/sbin/hometv-nightly-off >/dev/null <<'EOF'
#!/bin/sh
# Set the RTC alarm for the next 15:00, then power off cleanly.
set -eu
WAKE_AT=15:00
wake=$(date -d "today $WAKE_AT" +%s)
[ "$wake" -gt "$(date +%s)" ] || wake=$(date -d "tomorrow $WAKE_AT" +%s)
/usr/sbin/rtcwake -m no -t "$wake"
logger -t hometv-nightly-off "wake alarm set for $(date -d @"$wake"), powering off"
systemctl poweroff
EOF
sudo chmod 755 /usr/local/sbin/hometv-nightly-off
```

A systemd service runs the script, and a timer starts the service every day at 02:00:

```bash
sudo tee /etc/systemd/system/nightly-off.service >/dev/null <<'EOF'
[Unit]
Description=Set the RTC wake alarm and power off

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/hometv-nightly-off
EOF

sudo tee /etc/systemd/system/nightly-off.timer >/dev/null <<'EOF'
[Unit]
Description=Power off every night at 02:00

[Timer]
OnCalendar=*-*-* 02:00:00
# No Persistent=: a missed 02:00 must not shut the machine down right after it boots.

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now nightly-off.timer
```

### Verify

```bash
systemctl list-timers nightly-off.timer        # NEXT: tonight at 02:00
```

After the first shutdown and wake-up:

```bash
journalctl -b -1 -t hometv-nightly-off         # "wake alarm set for ..." (previous boot)
journalctl -b | head -1                        # this boot started at about 15:00
systemctl status hometv.service                # HomeTV is running
```

### Change the times

- **Power-on time**: edit `WAKE_AT=` in `/usr/local/sbin/hometv-nightly-off`. Nothing else needs
  to change; the new time applies from the next shutdown.
- **Shutdown time**: edit `OnCalendar=` in `/etc/systemd/system/nightly-off.timer`, then run
  `sudo systemctl daemon-reload && sudo systemctl restart nightly-off.timer`.

### Turn it off

```bash
sudo systemctl disable --now nightly-off.timer
sudo rtcwake -m disable                        # clear an alarm that is already set
```

## Good to know

- Anyone still watching at 02:00 is cut off: the shutdown does not check for active playback.
- Between 02:00 and 15:00 the server is off. The web and TV apps cannot reach it during that
  time, and there are no scans.
- **Power cuts**: after a power cut the machine stays off unless the BIOS option "Restore on AC
  Power Loss" (or similar) is set to *Power On*. If it is set to *Off*, the 15:00 alarm is lost
  too, because the alarm is only set when the 02:00 shutdown runs.
- The shutdown is a normal, clean `poweroff`: `hometv.service` stops the containers first
  (`docker compose stop`), and they start again at the next boot.
