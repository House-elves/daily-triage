import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class Notifier {

    private final Config config;

    Notifier(Config config) {
        this.config = config;
    }

    // ── Triage summary (plain text) ──

    void sendTriageSummary(Map<String, List<EmailTriage.TriageResult>> results) {
        int totalProcessed = 0;
        int totalMarked = 0;

        StringBuilder lines = new StringBuilder();
        lines.append("Daily Email Triage Summary\n");
        lines.append("Run at: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n\n");

        for (var entry : results.entrySet()) {
            String accountEmail = entry.getKey();
            List<EmailTriage.TriageResult> accountResults = entry.getValue();

            long kept = accountResults.stream().filter(r -> "KEEP_UNREAD".equals(r.decision())).count();
            long marked = accountResults.stream().filter(r -> "MARK_READ".equals(r.decision())).count();

            totalProcessed += accountResults.size();
            totalMarked += marked;

            lines.append("=== ").append(accountEmail).append(" ===\n");
            lines.append("  Processed: ").append(accountResults.size())
                    .append(" | Marked read: ").append(marked)
                    .append(" | Kept: ").append(kept).append("\n");

            List<EmailTriage.TriageResult> markedList = accountResults.stream()
                    .filter(r -> "MARK_READ".equals(r.decision())).toList();
            if (!markedList.isEmpty()) {
                lines.append("  Marked as read:\n");
                for (EmailTriage.TriageResult r : markedList) {
                    lines.append("    - ").append(r.subject()).append("\n");
                    lines.append("      from: ").append(r.from()).append(" -- ").append(r.reason()).append("\n");
                }
            }

            List<EmailTriage.TriageResult> keptList = accountResults.stream()
                    .filter(r -> "KEEP_UNREAD".equals(r.decision())).toList();
            if (!keptList.isEmpty()) {
                lines.append("  Kept unread:\n");
                for (EmailTriage.TriageResult r : keptList) {
                    lines.append("    - ").append(r.subject()).append("\n");
                    lines.append("      from: ").append(r.from()).append(" -- ").append(r.reason()).append("\n");
                }
            }

            lines.append("\n");
        }

        if (totalProcessed == 0) return;

        String subject = "Email Triage: " + totalMarked + "/" + totalProcessed + " marked read";
        sendPlainText(subject, lines.toString());
    }

    // ── Morning briefing (multipart HTML) ──

    void sendBriefing(String html) {
        String todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String subject = "Morning Briefing — " + todayStr;
        String plainFallback = "Your morning briefing for " + todayStr
                + ". View this email in an HTML-capable client for the full report.";

        try {
            Properties props = smtpProperties();
            Session session = Session.getInstance(props, authenticator());

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(config.gmailAddress));
            for (String to : config.sendTo.split(",")) {
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to.trim()));
            }
            msg.setSubject(subject);

            MimeMultipart multipart = new MimeMultipart("alternative");

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(plainFallback, "utf-8");
            multipart.addBodyPart(textPart);

            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(html, "text/html; charset=utf-8");
            multipart.addBodyPart(htmlPart);

            msg.setContent(multipart);

            Transport.send(msg);
            System.out.println("  Morning briefing sent!");
        } catch (Exception e) {
            System.err.println("  Failed to send briefing: " + e.getMessage());
        }
    }

    // ── Plain text email ──

    private void sendPlainText(String subject, String body) {
        try {
            Properties props = smtpProperties();
            Session session = Session.getInstance(props, authenticator());

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(config.gmailAddress));
            for (String to : config.sendTo.split(",")) {
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(to.trim()));
            }
            msg.setSubject(subject);
            msg.setText(body, "utf-8");

            Transport.send(msg);
            System.out.println("Triage summary sent.");
        } catch (Exception e) {
            System.err.println("Failed to send triage summary: " + e.getMessage());
        }
    }

    // ── Shared SMTP config ──

    private Properties smtpProperties() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        return props;
    }

    private Authenticator authenticator() {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.gmailAddress, config.gmailAppPassword);
            }
        };
    }
}
