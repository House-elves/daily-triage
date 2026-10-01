import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.mail.*;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.FlagTerm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EmailTriage {

    private static final int BATCH_SIZE = 25;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    record EmailInfo(
        String uid,
        String from,
        String to,
        String cc,
        String subject,
        String date,
        String listId,
        String bodyPreview,
        String messageId,
        String inReplyTo,
        String references
    ) {}

    record Decision(String decision, String reason) {}

    record TriageResult(String subject, String from, String decision, String reason) {}

    // ── Fetch unread emails via IMAP ──

    static List<EmailInfo> fetchUnreadEmails(Config.GmailAccount account, int maxEmails) {
        List<EmailInfo> emails = new ArrayList<>();
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", "imap.gmail.com");
        props.put("mail.imaps.port", "993");

        try {
            Session session = Session.getInstance(props);
            Store store = session.getStore("imaps");
            store.connect("imap.gmail.com", account.email(), account.password());

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));

            if (messages.length == 0) {
                inbox.close(false);
                store.close();
                return emails;
            }

            // Take the last maxEmails (most recent)
            int start = Math.max(0, messages.length - maxEmails);
            for (int i = start; i < messages.length; i++) {
                try {
                    Message msg = messages[i];
                    // Use message number as UID proxy (we'll re-fetch by UID later)
                    String uid = String.valueOf(msg.getMessageNumber());

                    // Try to get real UID if available
                    if (inbox instanceof UIDFolder uidFolder) {
                        uid = String.valueOf(uidFolder.getUID(msg));
                    }

                    String from = decodeHeader(msg.getHeader("From"));
                    String to = decodeHeader(msg.getHeader("To"));
                    String cc = decodeHeader(msg.getHeader("Cc"));
                    String subject = decodeHeader(msg.getHeader("Subject"));
                    String date = decodeHeader(msg.getHeader("Date"));
                    String listId = safeHeader(msg, "List-Id");
                    String messageId = safeHeader(msg, "Message-ID").strip();
                    String inReplyTo = safeHeader(msg, "In-Reply-To").strip();
                    String references = safeHeader(msg, "References").strip();

                    String body = getTextBody(msg);
                    if (body.length() > 800) {
                        body = body.substring(0, 800) + "...";
                    }

                    emails.add(new EmailInfo(uid, from, to, cc, subject, date,
                            listId, body, messageId, inReplyTo, references));
                } catch (Exception e) {
                    // Skip problematic messages
                }
            }

            inbox.close(false);
            store.close();
        } catch (Exception e) {
            System.err.println("  Error fetching from " + account.email() + ": " + e.getMessage());
        }

        return emails;
    }

    // ── Thread deduplication ──

    static String threadRootKey(EmailInfo em) {
        String refs = em.references();
        if (!refs.isEmpty()) {
            String[] parts = refs.split("\\s+");
            if (parts.length > 0 && !parts[0].isEmpty()) {
                return parts[0];
            }
        }
        return em.messageId().isEmpty() ? String.valueOf(System.identityHashCode(em)) : em.messageId();
    }

    static List<EmailInfo> deduplicateThreads(List<EmailInfo> emails) {
        Map<String, EmailInfo> threads = new LinkedHashMap<>();
        for (EmailInfo em : emails) {
            String root = threadRootKey(em);
            threads.putIfAbsent(root, em);
        }
        return new ArrayList<>(threads.values());
    }

    static Map<String, List<String>> collectThreadExtraUids(List<EmailInfo> allEmails, List<EmailInfo> deduped) {
        Set<String> dedupedUids = new HashSet<>();
        for (EmailInfo em : deduped) dedupedUids.add(em.uid());

        Map<String, String> rootToKeptUid = new HashMap<>();
        for (EmailInfo em : deduped) {
            rootToKeptUid.put(threadRootKey(em), em.uid());
        }

        Map<String, List<String>> extras = new HashMap<>();
        for (EmailInfo em : allEmails) {
            if (dedupedUids.contains(em.uid())) continue;
            String keptUid = rootToKeptUid.get(threadRootKey(em));
            if (keptUid != null) {
                extras.computeIfAbsent(keptUid, k -> new ArrayList<>()).add(em.uid());
            }
        }
        return extras;
    }

    // ── Threads owned by other elves ──

    /**
     * GitHub notifications for Dependabot's mvnpm bumps on Quarkus, e.g.
     * "Re: [quarkusio/quarkus] Bump org.mvnpm:marked from 17.0.5 to 18.0.14 (PR #57022)".
     * The mvnpm-dependabot elf validates every one of those PRs, comments on it,
     * and emails its own summary, so these notifications - quarkus-bot's
     * "/cc @phillip-kruger (mvnpm)" included - are already handled. Claude would
     * keep them unread, since a /cc is a direct mention.
     */
    private static final Pattern MVNPM_DEPENDABOT = Pattern.compile(
            "\\[quarkusio/quarkus] Bump org\\.mvnpm[\\w.-]*:");

    static String handledByElf(EmailInfo em) {
        if (em.from().contains("notifications@github.com")
                && MVNPM_DEPENDABOT.matcher(em.subject()).find()) {
            return "handled by the mvnpm-dependabot elf";
        }
        return null;
    }

    // ── Classification via Claude ──

    static Map<Integer, Decision> classifyBatch(List<EmailInfo> emails, String accountEmail) {
        if (emails.isEmpty()) return Map.of();

        StringBuilder summaries = new StringBuilder();
        for (int i = 0; i < emails.size(); i++) {
            EmailInfo em = emails.get(i);
            summaries.append("--- Email ").append(i + 1).append(" ---\n");
            summaries.append("From: ").append(em.from()).append("\n");
            summaries.append("To: ").append(em.to()).append("\n");
            summaries.append("CC: ").append(em.cc()).append("\n");
            summaries.append("Subject: ").append(em.subject()).append("\n");
            summaries.append("Date: ").append(em.date()).append("\n");
            summaries.append("List-Id: ").append(em.listId()).append("\n");
            summaries.append("Body preview:\n").append(em.bodyPreview()).append("\n\n");
        }

        String prompt = "You are an email triage assistant. The user's email address is: " + accountEmail + "\n\n"
                + "Below are unread emails from this account. For each email, decide whether the user needs to see it "
                + "(KEEP_UNREAD) or if it can be safely marked as read (MARK_READ).\n\n"
                + "Mark as read (MARK_READ) emails that are:\n"
                + "- Automated CI/CD notifications (build results, dependabot, renovate)\n"
                + "- Marketing emails, newsletters, promotional content\n"
                + "- Automated social media notifications\n"
                + "- Mass-distributed mailing list emails where the user is not directly addressed or mentioned\n"
                + "- Bot-generated messages (GitHub bots, JIRA bots, etc.)\n"
                + "- Routine system alerts that don't require action\n"
                + "- Automated subscription notifications the user didn't personally trigger\n\n"
                + "Keep unread (KEEP_UNREAD) emails that are:\n"
                + "- Personally addressed to the user (not just CC'd on a mass thread)\n"
                + "- Require a response or action from the user\n"
                + "- From managers, colleagues, or direct collaborators\n"
                + "- Urgent or time-sensitive content\n"
                + "- Direct mentions or explicit requests in otherwise automated threads\n"
                + "- Calendar invitations or meeting requests\n"
                + "- Anything you're uncertain about\n\n"
                + "IMPORTANT: Be conservative. When in doubt, choose KEEP_UNREAD.\n\n"
                + summaries
                + "Respond with a JSON array where each element has:\n"
                + "- \"index\": the email number (1-based)\n"
                + "- \"decision\": \"KEEP_UNREAD\" or \"MARK_READ\"\n"
                + "- \"reason\": brief explanation (under 15 words)\n\n"
                + "Respond ONLY with the JSON array, no other text.";

        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "-p", "--model", "sonnet");
            pb.redirectErrorStream(false);
            Process process = pb.start();

            try (var os = process.getOutputStream()) {
                os.write(prompt.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            boolean finished = process.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("  Claude classification timed out");
                return Map.of();
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                System.err.println("  Claude classification failed: " + stderr.substring(0, Math.min(300, stderr.length())));
                return Map.of();
            }

            // Extract JSON array
            int start = stdout.indexOf('[');
            int end = stdout.lastIndexOf(']') + 1;
            if (start >= 0 && end > start) {
                stdout = stdout.substring(start, end);
            }

            List<Map<String, Object>> decisions = MAPPER.readValue(stdout, new TypeReference<>() {});
            Map<Integer, Decision> result = new HashMap<>();
            for (Map<String, Object> d : decisions) {
                int index = ((Number) d.get("index")).intValue();
                String decision = (String) d.get("decision");
                String reason = (String) d.getOrDefault("reason", "");
                result.put(index, new Decision(decision, reason));
            }
            return result;
        } catch (Exception e) {
            System.err.println("  Error classifying emails: " + e.getMessage());
            return Map.of();
        }
    }

    static Map<Integer, Decision> classifyEmails(List<EmailInfo> emails, String accountEmail) {
        Map<Integer, Decision> allDecisions = new HashMap<>();
        for (int i = 0; i < emails.size(); i += BATCH_SIZE) {
            List<EmailInfo> batch = emails.subList(i, Math.min(i + BATCH_SIZE, emails.size()));
            Map<Integer, Decision> batchDecisions = classifyBatch(batch, accountEmail);
            for (var entry : batchDecisions.entrySet()) {
                allDecisions.put(entry.getKey() + i, entry.getValue());
            }
        }
        return allDecisions;
    }

    // ── Mark emails as read ──

    static void markAsRead(Config.GmailAccount account, List<String> uids, String label) {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", "imap.gmail.com");
        props.put("mail.imaps.port", "993");

        try {
            Session session = Session.getInstance(props);
            Store store = session.getStore("imaps");
            store.connect("imap.gmail.com", account.email(), account.password());

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            if (inbox instanceof UIDFolder uidFolder) {
                for (String uidStr : uids) {
                    try {
                        long uid = Long.parseLong(uidStr);
                        Message msg = uidFolder.getMessageByUID(uid);
                        if (msg != null) {
                            msg.setFlag(Flags.Flag.SEEN, true);
                            if (label != null && !label.isEmpty()) {
                                // Gmail-specific: apply label via X-GM-LABELS
                                try {
                                    applyGmailLabel(inbox, uidStr, label);
                                } catch (Exception labelEx) {
                                    // Label application is best-effort
                                }
                            }
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            inbox.close(false);
            store.close();
        } catch (Exception e) {
            System.err.println("  Error marking emails as read: " + e.getMessage());
        }
    }

    // ── Process a single account ──

    record AccountResult(List<TriageResult> results, List<String> markReadUids) {}

    static AccountResult processAccount(Config.GmailAccount account, int maxEmails,
                                         String label, boolean dryRun) {
        String accountEmail = account.email();
        System.out.println("\nProcessing " + accountEmail + "...");

        List<EmailInfo> allEmails = fetchUnreadEmails(account, maxEmails);
        if (allEmails.isEmpty()) {
            System.out.println("  No unread emails.");
            return new AccountResult(List.of(), List.of());
        }

        List<EmailInfo> emails = deduplicateThreads(allEmails);
        Map<String, List<String>> threadExtraUids = collectThreadExtraUids(allEmails, emails);

        // Threads another elf already owns are settled here, without asking Claude.
        Map<Integer, Decision> decisions = new HashMap<>();
        List<EmailInfo> toClassify = new ArrayList<>();
        List<Integer> classifyIndex = new ArrayList<>();
        for (int i = 0; i < emails.size(); i++) {
            String handledBy = handledByElf(emails.get(i));
            if (handledBy != null) {
                decisions.put(i + 1, new Decision("MARK_READ", handledBy));
            } else {
                toClassify.add(emails.get(i));
                classifyIndex.add(i + 1);
            }
        }

        System.out.println("  Found " + allEmails.size() + " unread emails ("
                + emails.size() + " threads, " + decisions.size() + " handled by elves), classifying...");
        Map<Integer, Decision> classified = classifyEmails(toClassify, accountEmail);
        classified.forEach((idx, d) -> decisions.put(classifyIndex.get(idx - 1), d));

        List<TriageResult> accountResults = new ArrayList<>();
        List<String> markReadUids = new ArrayList<>();

        for (int i = 0; i < emails.size(); i++) {
            EmailInfo em = emails.get(i);
            Decision decision = decisions.getOrDefault(i + 1, new Decision(
                    "KEEP_UNREAD", "classification unavailable, keeping safe"));

            accountResults.add(new TriageResult(
                    em.subject(), em.from(), decision.decision(), decision.reason()));

            if ("MARK_READ".equals(decision.decision())) {
                markReadUids.add(em.uid());
                List<String> extras = threadExtraUids.get(em.uid());
                if (extras != null) markReadUids.addAll(extras);
            }
        }

        long kept = accountResults.stream().filter(r -> "KEEP_UNREAD".equals(r.decision())).count();
        int marked = markReadUids.size();

        if (!markReadUids.isEmpty() && !dryRun) {
            System.out.println("  Marking " + marked + " emails as read + labeling (keeping " + kept + " unread)...");
            markAsRead(account, markReadUids, label);
        } else if (dryRun) {
            System.out.println("  [DRY RUN] Would mark " + marked + " as read, keep " + kept + " unread:");
            for (TriageResult r : accountResults) {
                String tag = "MARK_READ".equals(r.decision()) ? "READ" : "KEEP";
                System.out.println("    [" + tag + "] " + r.subject() + " -- " + r.reason());
            }
        } else {
            System.out.println("  All " + kept + " emails kept unread.");
        }

        return new AccountResult(accountResults, markReadUids);
    }

    // ── Helper methods ──

    private static void applyGmailLabel(Folder inbox, String uidStr, String label) {
        try {
            if (inbox instanceof org.eclipse.angus.mail.imap.IMAPFolder imapFolder) {
                imapFolder.doCommand(p -> {
                    p.simpleCommand(
                            "UID STORE " + uidStr + " +X-GM-LABELS (\"" + label + "\")",
                            null
                    );
                    return null;
                });
            }
        } catch (Exception ignored) {
            // Label application is best-effort
        }
    }

    private static String decodeHeader(String[] headers) {
        if (headers == null || headers.length == 0) return "";
        try {
            return MimeUtility.decodeText(headers[0]);
        } catch (Exception e) {
            return headers[0] != null ? headers[0] : "";
        }
    }

    private static String safeHeader(Message msg, String name) {
        try {
            String[] vals = msg.getHeader(name);
            if (vals != null && vals.length > 0 && vals[0] != null) {
                return vals[0];
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String getTextBody(Message msg) throws Exception {
        if (msg.isMimeType("text/plain")) {
            Object content = msg.getContent();
            return content != null ? content.toString() : "";
        }

        if (msg.isMimeType("text/html")) {
            Object content = msg.getContent();
            return content != null ? stripHtml(content.toString()) : "";
        }

        if (msg.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) msg.getContent();

            // First pass: look for text/plain
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) {
                    Object content = bp.getContent();
                    return content != null ? content.toString() : "";
                }
                if (bp.isMimeType("multipart/*")) {
                    String nested = getTextFromMultipart((Multipart) bp.getContent());
                    if (nested != null) return nested;
                }
            }

            // Second pass: fall back to text/html
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/html")) {
                    Object content = bp.getContent();
                    return content != null ? stripHtml(content.toString()) : "";
                }
            }
        }

        return "";
    }

    private static String getTextFromMultipart(Multipart mp) throws Exception {
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart bp = mp.getBodyPart(i);
            if (bp.isMimeType("text/plain")) {
                Object content = bp.getContent();
                return content != null ? content.toString() : "";
            }
        }
        return null;
    }

    private static String stripHtml(String text) {
        return HTML_TAG.matcher(text).replaceAll(" ");
    }
}
