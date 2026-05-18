import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

public class Config {

    static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "daily-triage");
    static final Path CONFIG_PATH = CONFIG_DIR.resolve("config");

    String gmailAddress;
    String gmailAppPassword;
    String sendTo;
    String displayName;
    String githubUser;
    Set<String> excludeOrgs;
    int maxEmails;
    String triageLabel;

    private Map<String, String> raw;

    record GmailAccount(String email, String password) {}

    static Config load() {
        if (!Files.exists(CONFIG_PATH)) {
            System.err.println("Config file not found: " + CONFIG_PATH);
            System.err.println("Run install.sh to set up daily-triage.");
            System.exit(1);
        }

        Map<String, String> raw = new HashMap<>();
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    raw.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
            System.exit(1);
        }

        Config c = new Config();
        c.raw = raw;
        c.gmailAddress = raw.getOrDefault("GMAIL_ADDRESS", "");
        c.gmailAppPassword = raw.getOrDefault("GMAIL_APP_PASSWORD", "");
        c.sendTo = raw.getOrDefault("SEND_TO", "");
        c.displayName = raw.getOrDefault("DISPLAY_NAME", "");
        c.githubUser = raw.getOrDefault("GITHUB_USER", "");
        c.excludeOrgs = parseSet(raw.getOrDefault("EXCLUDE_ORGS", ""));
        c.maxEmails = Integer.parseInt(raw.getOrDefault("MAX_EMAILS", "50"));
        c.triageLabel = raw.getOrDefault("TRIAGE_LABEL", "non-actionable");
        return c;
    }

    void validate(boolean requireEmail) {
        if (requireEmail) {
            requireField("GMAIL_ADDRESS", gmailAddress);
            requireField("GMAIL_APP_PASSWORD", gmailAppPassword);
            requireField("SEND_TO", sendTo);
        }
    }

    List<GmailAccount> getGmailAccounts() {
        List<GmailAccount> accounts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (!gmailAddress.isEmpty() && !gmailAppPassword.isEmpty()) {
            accounts.add(new GmailAccount(gmailAddress, gmailAppPassword));
            seen.add(gmailAddress.toLowerCase());
        }

        int i = 1;
        while (true) {
            String emailKey = "GMAIL_ACCOUNT_" + i;
            String passKey = "GMAIL_PASSWORD_" + i;
            if (!raw.containsKey(emailKey)) break;
            String addr = raw.get(emailKey);
            if (!seen.contains(addr.toLowerCase())) {
                accounts.add(new GmailAccount(addr, raw.getOrDefault(passKey, "")));
                seen.add(addr.toLowerCase());
            }
            i++;
        }
        return accounts;
    }

    List<String> getIcalUrls() {
        List<String> urls = new ArrayList<>();
        int i = 1;
        while (true) {
            String key = "ICAL_URL_" + i;
            if (!raw.containsKey(key)) break;
            urls.add(raw.get(key));
            i++;
        }
        return urls;
    }

    private static Set<String> parseSet(String csv) {
        return Set.of(csv.split(",")).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static void requireField(String name, String value) {
        if (value == null || value.isEmpty() || "CHANGE_ME".equals(value)) {
            System.err.println("Error: " + name + " not configured in " + CONFIG_PATH);
            System.exit(1);
        }
    }
}
