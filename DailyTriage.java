///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES Config.java
//SOURCES EmailTriage.java
//SOURCES CalendarFeed.java
//SOURCES GitHubIssues.java
//SOURCES BriefingGenerator.java
//SOURCES Notifier.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "daily-triage", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Daily email triage and morning briefing using Claude Code")
public class DailyTriage implements Callable<Integer> {

    @Option(names = "--dry-run", description = "Show decisions without marking anything")
    boolean dryRun;

    @Option(names = "--triage-only", description = "Run email triage only, skip morning briefing")
    boolean triageOnly;

    @Option(names = "--briefing-only", description = "Run morning briefing only, skip email triage")
    boolean briefingOnly;

    @Override
    public Integer call() {
        Config config = Config.load();
        config.validate(!dryRun && !briefingOnly);

        List<Config.GmailAccount> accounts = config.getGmailAccounts();
        if (accounts.isEmpty() && !briefingOnly) {
            System.err.println("No Gmail accounts configured.");
            return 1;
        }

        // Phase 1: Email triage
        Map<String, List<EmailTriage.TriageResult>> triageResults = new LinkedHashMap<>();

        if (!briefingOnly) {
            for (Config.GmailAccount account : accounts) {
                EmailTriage.AccountResult result = EmailTriage.processAccount(
                        account, config.maxEmails, config.triageLabel, dryRun);
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
                }
            }
        }

        // Phase 2: Morning briefing
        if (!triageOnly) {
            System.out.println("\n--- Morning Briefing ---");

            // Collect emails that need attention
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

            System.out.println("  Generating briefing...");
            String html = BriefingGenerator.generate(config, calendarEvents, githubIssues, attentionEmails);

            if (html != null && !dryRun) {
                Notifier notifier = new Notifier(config);
                notifier.sendBriefing(html);
            } else if (html != null) {
                try {
                    Path previewPath = Path.of("/tmp/daily-triage-preview.html");
                    Files.writeString(previewPath, html);
                    System.out.println("  [DRY RUN] Briefing preview saved to " + previewPath);
                } catch (IOException e) {
                    System.err.println("  Failed to save preview: " + e.getMessage());
                }
            } else {
                System.out.println("  Briefing generation failed.");
            }
        }

        System.out.println("\nDone!");
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new DailyTriage()).execute(args);
        System.exit(exitCode);
    }
}
