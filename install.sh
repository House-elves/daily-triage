#!/usr/bin/env bash
set -euo pipefail

CONFIG_DIR="$HOME/.config/daily-triage"
CONFIG_FILE="$CONFIG_DIR/config"
SCRIPT_NAME="daily-triage"
INSTALL_DIR="$HOME/.local/share/daily-triage"
BIN_DIR="$HOME/.local/bin"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== Daily Email Triage — Setup ==="
echo

# --- Prerequisites ---

missing=()
command -v java &>/dev/null || missing+=("java")
command -v jbang &>/dev/null || missing+=("jbang")
command -v gh &>/dev/null || missing+=("gh (GitHub CLI)")
command -v claude &>/dev/null || missing+=("claude (Claude Code CLI)")

if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Missing prerequisites: ${missing[*]}"
    echo "Please install them and re-run this script."
    exit 1
fi

if ! gh auth status &>/dev/null; then
    echo "GitHub CLI is not authenticated. Run 'gh auth login' first."
    exit 1
fi

echo "Prerequisites OK."
echo

# --- Gather config ---

read -rp "Your display name (shown in briefing): " display_name

default_gh_user=$(gh api user --jq '.login' 2>/dev/null || echo "")
read -rp "Your GitHub username [$default_gh_user]: " github_user
github_user="${github_user:-$default_gh_user}"

read -rp "GitHub orgs to exclude (comma-separated, or leave empty): " exclude_orgs

echo
echo "--- Primary Gmail Account ---"
echo "  Gmail App Passwords work with 2FA-enabled accounts."
echo "  Create one at: https://myaccount.google.com/apppasswords"
echo

read -rp "Gmail address: " gmail_address
read -srp "Gmail App Password: " gmail_app_password
echo

read -rp "Send emails to (comma-separated addresses) [$gmail_address]: " send_to
send_to="${send_to:-$gmail_address}"

# Additional Gmail accounts
extra_accounts=""
account_num=1
while true; do
    echo
    read -rp "Add another Gmail account to triage? [y/N] " add_more
    if [[ "${add_more,,}" != "y" ]]; then
        break
    fi
    account_num=$((account_num + 1))
    read -rp "Gmail address: " extra_email
    read -srp "Gmail App Password: " extra_pass
    echo
    extra_accounts+="GMAIL_ACCOUNT_${account_num}=${extra_email}\n"
    extra_accounts+="GMAIL_PASSWORD_${account_num}=${extra_pass}\n"
done

# iCal URLs
echo
echo "--- Calendar Feeds (optional) ---"
echo "  Google Calendar: Settings > calendar > 'Secret address in iCal format'"
echo

ical_urls=""
ical_num=0
while true; do
    ical_num=$((ical_num + 1))
    read -rp "iCal URL #${ical_num} (leave empty to stop): " ical_url
    if [[ -z "$ical_url" ]]; then
        break
    fi
    ical_urls+="ICAL_URL_${ical_num}=${ical_url}\n"
done

# Zulip servers
echo
echo "--- Zulip Chat (optional) ---"
echo "  Monitors unread mentions and watched streams in your Zulip instance."
echo "  To get your API key: log in to your Zulip server, go to"
echo "  Settings > Account & privacy > API key, and click 'Get API key'."
echo

zulip_servers=""
zulip_watches=""
zulip_num=0
while true; do
    zulip_num=$((zulip_num + 1))
    read -rp "Zulip server URL #${zulip_num} (e.g. quarkusio.zulipchat.com, leave empty to stop): " zulip_url
    if [[ -z "$zulip_url" ]]; then
        break
    fi
    read -rp "  Your email on this server: " zulip_email
    read -srp "  API key: " zulip_key
    echo
    zulip_servers+="ZULIP_SERVER_${zulip_num}=${zulip_url}\n"
    zulip_servers+="ZULIP_EMAIL_${zulip_num}=${zulip_email}\n"
    zulip_servers+="ZULIP_API_KEY_${zulip_num}=${zulip_key}\n"
done

if [[ -n "$zulip_servers" ]]; then
    echo
    echo "  Watch specific streams/topics for activity (format: stream or stream:topic)."
    echo "  Watched messages are marked read once the briefing is sent."
    echo "  Use * to summarise ALL unread messages (newest 100) without marking them read."
    watch_num=0
    while true; do
        watch_num=$((watch_num + 1))
        read -rp "  Watch #${watch_num} (leave empty to stop): " watch
        if [[ -z "$watch" ]]; then
            break
        fi
        zulip_watches+="ZULIP_WATCH_${watch_num}=${watch}\n"
    done
fi

read -rp "Max emails to fetch per account [50]: " max_emails
max_emails="${max_emails:-50}"

read -rp "Triage label for marked-read emails [non-actionable]: " triage_label
triage_label="${triage_label:-non-actionable}"

read -rp "Schedule (systemd OnCalendar format) [*-*-* 08:00:00]: " schedule
schedule="${schedule:-*-*-* 08:00:00}"

echo

# --- Write config ---

mkdir -p "$CONFIG_DIR"
cat > "$CONFIG_FILE" <<EOF
GMAIL_ADDRESS=$gmail_address
GMAIL_APP_PASSWORD=$gmail_app_password
SEND_TO=$send_to
DISPLAY_NAME=$display_name
GITHUB_USER=$github_user
EXCLUDE_ORGS=$exclude_orgs
MAX_EMAILS=$max_emails
TRIAGE_LABEL=$triage_label
SCHEDULE=$schedule
EOF

# Append extra accounts
if [[ -n "$extra_accounts" ]]; then
    echo -e "$extra_accounts" >> "$CONFIG_FILE"
fi

# Append iCal URLs
if [[ -n "$ical_urls" ]]; then
    echo -e "$ical_urls" >> "$CONFIG_FILE"
fi

# Append Zulip servers
if [[ -n "$zulip_servers" ]]; then
    echo -e "$zulip_servers" >> "$CONFIG_FILE"
fi

# Append Zulip watches
if [[ -n "$zulip_watches" ]]; then
    echo -e "$zulip_watches" >> "$CONFIG_FILE"
fi

chmod 600 "$CONFIG_FILE"
echo "Config written to $CONFIG_FILE"

# --- Install Java sources ---

mkdir -p "$INSTALL_DIR"
cp "$SCRIPT_DIR"/*.java "$INSTALL_DIR/"
echo "Java sources installed to $INSTALL_DIR"

# --- Create wrapper script ---

mkdir -p "$BIN_DIR"

JBANG_BIN=$(dirname "$(command -v jbang)" 2>/dev/null || echo "")
JAVA_BIN=$(dirname "$(command -v java)" 2>/dev/null || echo "")

cat > "$BIN_DIR/$SCRIPT_NAME" <<WRAPPER
#!/usr/bin/env bash
export PATH="$JBANG_BIN:$JAVA_BIN:$BIN_DIR:\$PATH"
exec jbang "$INSTALL_DIR/DailyTriage.java" "\$@"
WRAPPER
chmod +x "$BIN_DIR/$SCRIPT_NAME"
echo "Wrapper script installed to $BIN_DIR/$SCRIPT_NAME"

# --- Detect OS and install scheduler ---

install_systemd() {
    local systemd_dir="$HOME/.config/systemd/user"
    mkdir -p "$systemd_dir"

    cat > "$systemd_dir/$SCRIPT_NAME.service" <<EOF
[Unit]
Description=Daily email triage and morning briefing

[Service]
Type=oneshot
ExecStart=$BIN_DIR/$SCRIPT_NAME
Environment=HOME=$HOME
Environment=PATH=$JBANG_BIN:$JAVA_BIN:$BIN_DIR:/usr/bin:/bin

[Install]
WantedBy=default.target
EOF

    cat > "$systemd_dir/$SCRIPT_NAME.timer" <<EOF
[Unit]
Description=Run daily email triage on schedule

[Timer]
OnCalendar=$schedule
Persistent=true

[Install]
WantedBy=timers.target
EOF

    systemctl --user daemon-reload
    systemctl --user enable --now "$SCRIPT_NAME.timer"

    if command -v loginctl &>/dev/null; then
        loginctl enable-linger "$USER" 2>/dev/null || true
    fi

    echo "Systemd timer enabled (schedule: $schedule)."
}

install_launchd() {
    local plist_dir="$HOME/Library/LaunchAgents"
    local plist_file="$plist_dir/com.house-elves.daily-triage.plist"
    mkdir -p "$plist_dir"

    # Default: daily at 8 AM
    local hour=8 minute=0

    cat > "$plist_file" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.house-elves.daily-triage</string>
    <key>ProgramArguments</key>
    <array>
        <string>$BIN_DIR/$SCRIPT_NAME</string>
    </array>
    <key>StartCalendarInterval</key>
    <dict>
        <key>Hour</key>
        <integer>$hour</integer>
        <key>Minute</key>
        <integer>$minute</integer>
    </dict>
    <key>StandardOutPath</key>
    <string>$CONFIG_DIR/stdout.log</string>
    <key>StandardErrorPath</key>
    <string>$CONFIG_DIR/stderr.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>$JBANG_BIN:$JAVA_BIN:$BIN_DIR:/usr/local/bin:/usr/bin:/bin</string>
        <key>HOME</key>
        <string>$HOME</string>
    </dict>
</dict>
</plist>
EOF

    launchctl unload "$plist_file" 2>/dev/null || true
    launchctl load "$plist_file"
    echo "Launchd agent installed."
}

case "$(uname -s)" in
    Linux)  install_systemd ;;
    Darwin) install_launchd ;;
    *)      echo "Unsupported OS. Run manually: $BIN_DIR/$SCRIPT_NAME" ;;
esac

echo
echo "=== Setup complete! ==="
echo

# --- Offer dry run ---

read -rp "Run a dry-run now? [Y/n] " run_test
if [[ "${run_test,,}" != "n" ]]; then
    "$BIN_DIR/$SCRIPT_NAME" --dry-run
fi
