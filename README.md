# daily-triage

A House Elf that triages your email inbox and sends a morning briefing using [Claude Code](https://claude.com/claude-code).

Runs in two phases every morning:

**Phase 1 — Email triage:** Connects to your Gmail accounts via IMAP, fetches unread emails, deduplicates by thread, and uses Claude to classify each as actionable or noise. Noise gets marked as read and labeled. A plain-text triage summary is emailed to you.

**Phase 2 — Morning briefing:** Collects today's calendar events (from Google Calendar iCal feeds), your open GitHub issues, unread Zulip messages (mentions and watched streams), and the emails that survived triage. Claude generates a polished HTML briefing email — including a short summary of Zulip discussions — so you can scan your day in under 30 seconds.

## Prerequisites

- **Java 17+** (via SDKMAN or system package manager)
- **[JBang](https://www.jbang.dev/)** — `sdk install jbang` or `curl -Ls https://sh.jbang.dev | bash`
- **[GitHub CLI](https://cli.github.com/)** (`gh`) — authenticated with `gh auth login`
- **[Claude Code CLI](https://claude.com/claude-code)** (`claude`)
- **Gmail account(s)** with [App Passwords](https://myaccount.google.com/apppasswords)

## Install

### Quick install (via JBang)

```bash
jbang app install daily-triage@House-elves/daily-triage
```

Then run `install.sh` for interactive setup (config, schedule).

### Manual install (from source)

```bash
git clone https://github.com/House-elves/daily-triage.git
cd daily-triage
./install.sh
```

The installer will:

1. Check prerequisites (java, jbang, gh, claude)
2. Prompt for configuration (Gmail accounts, calendar feeds, Zulip servers, GitHub user, schedule)
3. Write config to `~/.config/daily-triage/config` (chmod 600)
4. Install to `~/.local/bin/daily-triage`
5. Set up a scheduler:
   - **Linux**: systemd timer (default: daily at 8 AM)
   - **macOS**: launchd plist
6. Offer a dry-run test

## Usage

```bash
# Dry run — show decisions without marking anything, save briefing preview
daily-triage --dry-run

# Run everything (triage + briefing)
daily-triage

# Triage only — skip morning briefing
daily-triage --triage-only

# Briefing only — skip email triage
daily-triage --briefing-only
```

## Configuration

Stored in `~/.config/daily-triage/config`:

| Key | Description | Default |
|-----|-------------|---------|
| `GMAIL_ADDRESS` | Primary Gmail sender address | -- |
| `GMAIL_APP_PASSWORD` | Primary Gmail App Password | -- |
| `SEND_TO` | Email recipients (comma-separated) | -- |
| `DISPLAY_NAME` | Name shown in briefing greeting | -- |
| `GITHUB_USER` | Your GitHub username | -- |
| `EXCLUDE_ORGS` | Comma-separated orgs to skip | -- |
| `MAX_EMAILS` | Max emails to fetch per account | `50` |
| `TRIAGE_LABEL` | Gmail label applied to marked-read emails | `non-actionable` |
| `SCHEDULE` | systemd OnCalendar expression | `*-*-* 08:00:00` |
| `GMAIL_ACCOUNT_N` | Additional Gmail address (N=2,3,...) | -- |
| `GMAIL_PASSWORD_N` | Additional Gmail App Password | -- |
| `ICAL_URL_N` | Google Calendar iCal feed URL (N=1,2,...) | -- |
| `ZULIP_SERVER_N` | Zulip server hostname (N=1,2,...) | -- |
| `ZULIP_EMAIL_N` | Your email on the Zulip server | -- |
| `ZULIP_API_KEY_N` | Zulip API key (Settings > Account & privacy > API key) | -- |
| `ZULIP_WATCH_N` | Stream or stream:topic to watch (N=1,2,...) | -- |

## Zulip integration

Optionally monitors your Zulip instance for unread messages. Two types of messages are surfaced in the briefing:

- **Mentions** — any unread message where you are @-mentioned, across all streams
- **Watched streams** — unread activity in streams (or specific topics) you configure with `ZULIP_WATCH_N`

Watch entries use the format `stream` (all topics) or `stream:topic` (specific topic). Messages that are both a mention and in a watched stream appear once, flagged as a mention.

The briefing summarizes Zulip discussions by topic so you get the gist without reading every message.

To get your API key: log in to your Zulip server, go to Settings > Account & privacy > API key, and click "Get API key".

## What gets marked as read

Claude classifies each email and marks as read:
- Automated CI/CD notifications (build results, dependabot, renovate)
- Marketing emails, newsletters, promotional content
- Automated social media notifications
- Mass-distributed mailing list messages where you are not directly addressed
- Bot-generated messages (GitHub bots, JIRA bots, etc.)
- Routine system alerts that do not require action

## What stays unread

- Emails personally addressed to you
- Messages requiring a response or action
- From managers, colleagues, or direct collaborators
- Urgent or time-sensitive content
- Direct mentions in otherwise automated threads
- Calendar invitations
- Anything uncertain (conservative by default)

## Managing the timer

```bash
# Check when the next run is scheduled
systemctl --user list-timers

# Disable the timer
systemctl --user disable --now daily-triage.timer

# Re-enable the timer
systemctl --user enable --now daily-triage.timer

# Check logs from the last run
journalctl --user -u daily-triage.service

# Run manually
daily-triage --dry-run
```
