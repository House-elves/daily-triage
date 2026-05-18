import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class BriefingGenerator {

    record AttentionEmail(String account, String from, String subject, String reason) {}

    static String generate(Config config, List<CalendarFeed.CalendarEvent> calendarEvents,
                            List<GitHubIssues.IssueInfo> githubIssues,
                            List<AttentionEmail> attentionEmails) {

        String displayName = config.displayName;
        String todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy"));

        StringBuilder sections = new StringBuilder();
        sections.append("Display name: ").append(displayName).append("\n");
        sections.append("Date: ").append(todayStr).append("\n\n");

        // Calendar
        sections.append("=== TODAY'S CALENDAR ===\n");
        if (calendarEvents != null && !calendarEvents.isEmpty()) {
            for (CalendarFeed.CalendarEvent ev : calendarEvents) {
                String loc = ev.location() != null && !ev.location().isEmpty()
                        ? " (" + ev.location() + ")" : "";
                sections.append("  ").append(ev.time()).append(" — ").append(ev.summary()).append(loc).append("\n");
            }
        } else {
            sections.append("  No events today.\n");
        }
        sections.append("\n");

        // Emails
        sections.append("=== EMAILS NEEDING ATTENTION ===\n");
        if (attentionEmails != null && !attentionEmails.isEmpty()) {
            for (AttentionEmail em : attentionEmails) {
                sections.append("  [").append(em.account()).append("] From: ").append(em.from()).append("\n");
                sections.append("    Subject: ").append(em.subject()).append("\n");
                sections.append("    Why: ").append(em.reason()).append("\n");
            }
        } else {
            sections.append("  Inbox zero — no emails need your attention.\n");
        }
        sections.append("\n");

        // GitHub issues
        sections.append("=== OUTSTANDING GITHUB ISSUES ===\n");
        if (githubIssues != null && !githubIssues.isEmpty()) {
            for (GitHubIssues.IssueInfo issue : githubIssues) {
                String labels = issue.labels() != null && !issue.labels().isEmpty()
                        ? " [" + issue.labels() + "]" : "";
                sections.append("  ").append(issue.repo()).append("#").append(issue.number())
                        .append(": ").append(issue.title()).append(labels).append("\n");
                sections.append("    ").append(issue.url()).append("\n");
            }
        } else {
            sections.append("  No open issues assigned to you.\n");
        }

        String data = sections.toString();

        String prompt = "Generate a polished HTML morning briefing email from the data below.\n\n"
                + "Requirements:\n"
                + "- Clean, modern design with inline CSS (no external stylesheets)\n"
                + "- Use a warm, professional greeting with the display name\n"
                + "- Organize into clear sections: Calendar, Emails, GitHub Issues\n"
                + "- Use a color scheme that looks good in both light and dark email clients\n"
                + "- Make GitHub issue URLs clickable links\n"
                + "- Keep it scannable — the reader should get the full picture in under 30 seconds\n"
                + "- Do NOT include any preamble or explanation, output ONLY the HTML (starting with <!DOCTYPE html>)\n\n"
                + "Data:\n" + data;

        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "-p", "--model", "sonnet");
            pb.redirectErrorStream(false);
            Process process = pb.start();

            try (var os = process.getOutputStream()) {
                os.write(prompt.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("  Briefing generation timed out");
                return null;
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                System.err.println("  Briefing generation failed: "
                        + stderr.substring(0, Math.min(300, stderr.length())));
                return null;
            }

            // Find the start of HTML
            int start = stdout.indexOf("<!DOCTYPE");
            if (start < 0) start = stdout.indexOf("<html");
            if (start < 0) start = 0;
            return stdout.substring(start);
        } catch (IOException | InterruptedException e) {
            System.err.println("  Error generating briefing: " + e.getMessage());
            return null;
        }
    }
}
