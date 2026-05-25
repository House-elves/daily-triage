///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES Config.java
//SOURCES EmailTriage.java
//SOURCES CalendarFeed.java
//SOURCES GitHubIssues.java
//SOURCES ZulipChat.java
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
        config.validate(!dryRun && !briefingOnly);

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
                    decisions.add(d);
                }
                accountData.put("decisions", decisions);
                triageAccounts.add(accountData);

                if (!result.results().isEmpty()) {
                    triageResults.put(account.email(), result.results());
                }
            }

            if (!triageResults.isEmpty() && !dryRun) {
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

            System.out.println("  Generating briefing...");
            briefingHtml = BriefingGenerator.generate(config, calendarEvents, githubIssues, attentionEmails, zulipResult.messages());

            if (briefingHtml != null && !dryRun) {
                Notifier notifier = new Notifier(config);
                notifier.sendBriefing(briefingHtml);
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
