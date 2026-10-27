# systemd units

Only the two timers live here; the long-running processes are in `deploy/compose.yaml` (see its header for why).
Install (deploy.sh does not; the RUNBOOK does it once on the server):

    sudo cp deploy/systemd/zandy-*.service deploy/systemd/zandy-*.timer /etc/systemd/system/
    sudo systemctl daemon-reload
    sudo systemctl enable --now zandy-backup.timer zandy-restore-test.timer
    systemctl list-timers 'zandy-*'
    journalctl -u zandy-backup.service -n 50

The units assume the login user `ubuntu` (OVH's default on Ubuntu). Change `User=` if the account is named differently.

Install the timers only on the server that runs production. A staging-only host (4 GB VPS-1) holds sample data:
there `backup.sh` without options does nothing, and the one-off test is `backup.sh --staging` then `restore-test.sh --staging`.
