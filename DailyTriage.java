///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES Config.java
//SOURCES EmailTriage.java
//SOURCES CalendarFeed.java
//SOURCES GitHubIssues.java
//SOURCES ZulipChat.java
//SOURCES ZulipDigest.java
//SOURCES BriefingGenerator.java
//SOURCES Notifier.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Command(name = "daily-triage", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Daily email triage and morning briefing using Claude Code")
public class DailyTriage implements Callable<Integer> {

    @Option(names = "--dry-run", description = "Show decisions without marking anything")
    boolean dryRun;

    @Option(names = "--triage-only", description = "Run email triage only, skip morning briefing")
    boolean triageOnly;

    @Option(names = "--briefing-only", description = "Run morning briefing only, skip email triage")
    boolean briefingOnly;

    @Option(names = "--no-email", description = "Don't email the triage summary or briefing; the run is only saved "
            + "(for Elf Desk, which shows it). Emails and Zulip messages are still marked read as usual.")
    boolean noEmail;

    @Option(names = "--zulip-only", description = "Fetch recent Zulip messages (see --zulip-hours) and unread mentions, "
            + "digest them per topic and save the run. Marks nothing read and sends nothing.")
    boolean zulipOnly;

    @Option(names = "--mark-read", paramLabel = "ACCOUNT", description = "Mark the threads of --message-id read in this "
            + "Gmail account (one of the configured ones), then exit. For Elf Desk's Done button.")
    String markReadAccount;

    @Option(names = "--message-id", paramLabel = "ID", description = "With --mark-read: a Message-ID whose thread to mark read (repeatable)")
    List<String> messageIds = new ArrayList<>();

    @Option(names = "--zulip-hours", defaultValue = "24", description = "How far back --zulip-only looks (default: ${DEFAULT-VALUE})")
    int zulipHours;

    private static final int RETRY_DELAY_MINUTES = 60;
    private static final int MAX_RETRIES = 2;

    @Override
    public Integer call() {
        long startTime = System.currentTimeMillis();

        if (!waitForNetwork()) {
            System.err.println("No network connectivity after retrying. Aborting.");
            return 1;
        }

        Config config = Config.load();
        if (markReadAccount != null) return markRead(config);
        if (zulipOnly) return zulipRun(config, startTime);
        config.validate(!dryRun && !briefingOnly && !noEmail);

        List<Config.GmailAccount> accounts = config.getGmailAccounts();
        if (accounts.isEmpty() && !briefingOnly) {
            System.err.println("No Gmail accounts configured.");
            return 1;
        }

        String mode = triageOnly ? "triage-only" : briefingOnly ? "briefing-only" : "full";
        Map<String, Object> runData = new LinkedHashMap<>();
        runData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        runData.put("mode", mode);
        runData.put("dryRun", dryRun);
        runData.put("emailed", !dryRun && !noEmail);
        String status = "success";

        // Phase 1: Email triage
        Map<String, List<EmailTriage.TriageResult>> triageResults = new LinkedHashMap<>();
        List<Map<String, Object>> triageAccounts = new ArrayList<>();

        if (!briefingOnly) {
            for (Config.GmailAccount account : accounts) {
                EmailTriage.AccountResult result = EmailTriage.processAccount(
                        account, config.maxEmails, config.triageLabel, dryRun);

                Map<String, Object> accountData = new LinkedHashMap<>();
                accountData.put("email", account.email());
                accountData.put("processed", result.results().size());
                accountData.put("markedRead", result.results().stream().filter(r -> "MARK_READ".equals(r.decision())).count());
                accountData.put("keptUnread", result.results().stream().filter(r -> "KEEP_UNREAD".equals(r.decision())).count());

                List<Map<String, String>> decisions = new ArrayList<>();
                for (EmailTriage.TriageResult r : result.results()) {
                    Map<String, String> d = new LinkedHashMap<>();
                    d.put("subject", r.subject());
                    d.put("from", r.from());
                    d.put("decision", r.decision());
                    d.put("reason", r.reason());
                    d.put("messageId", r.messageId());
                    decisions.add(d);
                }
                accountData.put("decisions", decisions);
                triageAccounts.add(accountData);

                if (!result.results().isEmpty()) {
                    triageResults.put(account.email(), result.results());
                }
            }

            if (!triageResults.isEmpty() && !dryRun && !noEmail) {
                System.out.println("\nSending triage summary...");
                try {
                    Notifier notifier = new Notifier(config);
                    notifier.sendTriageSummary(triageResults);
                } catch (Exception e) {
                    System.err.println("Failed to send triage summary: " + e.getMessage());
                    status = "partial";
                }
            }
        }

        Map<String, Object> triageData = new LinkedHashMap<>();
        triageData.put("accounts", triageAccounts);
        runData.put("triage", triageData);

        // Phase 2: Morning briefing
        Map<String, Object> briefingData = new LinkedHashMap<>();
        String briefingHtml = null;

        if (!triageOnly) {
            System.out.println("\n--- Morning Briefing ---");

            List<BriefingGenerator.AttentionEmail> attentionEmails = new ArrayList<>();
            for (var entry : triageResults.entrySet()) {
                for (EmailTriage.TriageResult r : entry.getValue()) {
                    if ("KEEP_UNREAD".equals(r.decision())) {
                        attentionEmails.add(new BriefingGenerator.AttentionEmail(
                                entry.getKey(), r.from(), r.subject(), r.reason()));
                    }
                }
            }

            System.out.println("  Fetching calendar events...");
            List<CalendarFeed.CalendarEvent> calendarEvents = CalendarFeed.fetchEvents(config.getIcalUrls());
            System.out.println("  Found " + calendarEvents.size() + " events today.");

            System.out.println("  Fetching GitHub issues...");
            List<GitHubIssues.IssueInfo> githubIssues = GitHubIssues.fetch(config);
            System.out.println("  Found " + githubIssues.size() + " open issues.");

            System.out.println("  Fetching Zulip messages...");
            ZulipChat.FetchResult zulipResult = ZulipChat.fetch(config);
            System.out.println("  Found " + zulipResult.messages().size() + " unread Zulip messages.");

            briefingData.put("calendarEvents", calendarEvents.size());
            briefingData.put("githubIssues", githubIssues.size());
            briefingData.put("zulipMessages", zulipResult.messages().size());
            runData.put("zulip", zulipData(zulipResult, Map.of(), !dryRun));

            System.out.println("  Generating briefing...");
            briefingHtml = BriefingGenerator.generate(config, calendarEvents, githubIssues, attentionEmails, zulipResult.messages());

            if (briefingHtml != null && !dryRun) {
                if (!noEmail) new Notifier(config).sendBriefing(briefingHtml);
                ZulipChat.markAsRead(zulipResult.watchMessageIds());
                briefingData.put("generated", true);
            } else if (briefingHtml != null) {
                try {
                    Path previewPath = Path.of("/tmp/daily-triage-preview.html");
                    Files.writeString(previewPath, briefingHtml);
                    System.out.println("  [DRY RUN] Briefing preview saved to " + previewPath);
                } catch (IOException e) {
                    System.err.println("  Failed to save preview: " + e.getMessage());
                }
                briefingData.put("generated", true);
            } else {
                System.out.println("  Briefing generation failed.");
                briefingData.put("generated", false);
                status = "partial";
            }
        }

        runData.put("briefing", briefingData);
        runData.put("durationMs", System.currentTimeMillis() - startTime);
        runData.put("status", status);

        saveRunHistory(runData, briefingHtml);

        System.out.println("\nDone!");
        return 0;
    }

    /** A Zulip-only run: recent messages and a digest per topic, saved for Elf Desk. Nothing is marked read. */
    /** --mark-read: each message's thread marked read, one JSON line of what was marked. */
    private int markRead(Config config) {
        Config.GmailAccount account = config.getGmailAccounts().stream()
                .filter(a -> a.email().equalsIgnoreCase(markReadAccount)).findFirst().orElse(null);
        if (account == null) {
            System.err.println("Not a configured Gmail account: " + markReadAccount);
            return 2;
        }
        if (messageIds.isEmpty()) {
            System.err.println("--mark-read needs at least one --message-id");
            return 2;
        }
        int marked = 0;
        try {
            for (String id : messageIds) marked += EmailTriage.markThreadRead(account, id);
        } catch (Exception e) {
            System.err.println("Couldn't mark it read: " + e.getMessage());
            return 1;
        }
        System.out.println("{\"marked\": " + marked + "}");
        return 0;
    }

    private int zulipRun(Config config, long startTime) {
        if (config.getZulipServers().isEmpty()) {
            System.err.println("No Zulip servers configured.");
            return 1;
        }
        System.out.println("Fetching Zulip messages...");
        List<ZulipChat.ZulipMessage> messages = ZulipChat.fetchRecent(config, zulipHours);
        System.out.println("  Found " + messages.size() + " Zulip messages from the last " + zulipHours + " hours. Digesting...");
        Map<String, String> digests = ZulipDigest.generate(messages);

        Map<String, Object> runData = new LinkedHashMap<>();
        runData.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        runData.put("mode", "zulip");
        runData.put("dryRun", dryRun);
        runData.put("zulipHours", zulipHours);
        runData.put("zulip", zulipData(new ZulipChat.FetchResult(messages, Map.of()), digests, false));
        runData.put("durationMs", System.currentTimeMillis() - startTime);
        runData.put("status", "success");
        saveRunHistory(runData, null);
        System.out.println("\nDone!");
        return 0;
    }

    /**
     * The Zulip messages of a run, for Elf Desk's Zulip tab.
     *
     * @param markRead whether the briefing marks the watched ones read afterwards (they're flagged so)
     */
    private static Map<String, Object> zulipData(ZulipChat.FetchResult result, Map<String, String> digests,
            boolean markRead) {
        Set<Long> marked = new HashSet<>();
        if (markRead) result.watchMessageIds().values().forEach(marked::addAll);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ZulipChat.ZulipMessage m : result.messages()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("server", m.server());
            d.put("stream", m.stream());
            d.put("topic", m.topic());
            d.put("sender", m.sender());
            d.put("content", m.content());
            d.put("url", m.url());
            d.put("time", m.time());
            d.put("mention", m.isMention());
            d.put("involvesMe", m.involvesMe());
            d.put("unread", m.unread());
            d.put("markedRead", marked.contains(m.id()));
            messages.add(d);
        }
        Map<String, Object> zulip = new LinkedHashMap<>();
        zulip.put("messages", messages);
        zulip.put("digests", digests);
        return zulip;
    }

    private void saveRunHistory(Map<String, Object> runData, String briefingHtml) {
        try {
            Path runsDir = Config.CONFIG_DIR.resolve("runs");
            Files.createDirectories(runsDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

            Path runFile = runsDir.resolve(timestamp + ".json");
            mapper.writeValue(runFile.toFile(), runData);

            if (briefingHtml != null) {
                Path htmlFile = runsDir.resolve(timestamp + ".html");
                Files.writeString(htmlFile, briefingHtml);
            }

            pruneOldRuns(runsDir, 90);
        } catch (IOException e) {
            System.err.println("Failed to save run history: " + e.getMessage());
        }
    }

    private void pruneOldRuns(Path runsDir, int maxDays) {
        try (Stream<Path> files = Files.list(runsDir)) {
            Instant cutoff = Instant.now().minus(maxDays, ChronoUnit.DAYS);
            files.filter(p -> {
                try {
                    return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                } catch (IOException e) {
                    return false;
                }
            }).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private boolean waitForNetwork() {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (isNetworkAvailable()) return true;
            if (attempt < MAX_RETRIES) {
                System.out.println("Network not available. Retry " + (attempt + 1) + "/" + MAX_RETRIES
                        + " in " + RETRY_DELAY_MINUTES + " minutes...");
                try {
                    TimeUnit.MINUTES.sleep(RETRY_DELAY_MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean isNetworkAvailable() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.google.com"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DailyTriage()).execute(args);
        System.exit(exitCode);
    }
}
