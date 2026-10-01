import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class ZulipChat {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MESSAGES = 100;
    /** Watch value meaning every unread message, not one stream. */
    static final String ALL_UNREAD = "*";

    record ZulipMessage(String server, String stream, String topic, String sender,
                        String content, String url, boolean isMention,
                        boolean involvesMe, String fullText) {
        ZulipMessage(String server, String stream, String topic, String sender,
                     String content, String url, boolean isMention) {
            this(server, stream, topic, sender, content, url, isMention, false, "");
        }
    }

    record FetchResult(List<ZulipMessage> messages, Map<Config.ZulipServer, List<Long>> watchMessageIds) {}

    static FetchResult fetch(Config config) {
        List<Config.ZulipServer> servers = config.getZulipServers();
        if (servers.isEmpty()) return new FetchResult(List.of(), Map.of());

        List<String> watches = config.getZulipWatchTopics();
        List<ZulipMessage> allMessages = new ArrayList<>();
        Map<Config.ZulipServer, List<Long>> watchMessageIds = new LinkedHashMap<>();

        for (Config.ZulipServer server : servers) {
            try {
                List<ZulipMessage> mentions = fetchMessages(server,
                        "[{\"operator\":\"is\",\"operand\":\"mentioned\"},{\"operator\":\"is\",\"operand\":\"unread\"}]");
                allMessages.addAll(mentions.stream()
                        .map(m -> new ZulipMessage(m.server(), m.stream(), m.topic(), m.sender(), m.content(), m.url(), true,
                                true, m.fullText()))
                        .toList());

                Set<Long> mentionIds = new HashSet<>();
                for (ZulipMessage m : mentions) {
                    mentionIds.add(extractMessageId(m.url()));
                }

                List<Long> serverWatchIds = new ArrayList<>();
                Set<Long> included = new HashSet<>(mentionIds);
                Set<Long> followed = all(watches) && config.zulipMarkAllRead()
                        ? ids(fetchMessages(server, "[{\"operator\":\"is\",\"operand\":\"followed\"},{\"operator\":\"is\",\"operand\":\"unread\"}]"))
                        : Set.of();
                List<String> keywords = config.zulipKeepUnreadKeywords();
                int kept = 0;
                for (String watch : watches) {
                    // "*" summarises ALL unread messages. A named watch is marked
                    // read once the briefing covers it. "*" messages are too, with
                    // ZULIP_MARK_ALL_READ=true - except the ones that involve you.
                    boolean all = watch.strip().equals(ALL_UNREAD);
                    String narrow = all ? "[{\"operator\":\"is\",\"operand\":\"unread\"}]" : buildWatchNarrow(watch);
                    if (narrow == null) continue;

                    List<ZulipMessage> watchMessages = fetchMessages(server, narrow);
                    for (ZulipMessage m : watchMessages) {
                        long id = extractMessageId(m.url());
                        if (included.add(id)) {
                            allMessages.add(m);
                            if (!all) {
                                serverWatchIds.add(id);
                            } else if (config.zulipMarkAllRead()) {
                                if (involvesMe(m, followed.contains(id), keywords)) {
                                    kept++;
                                } else {
                                    serverWatchIds.add(id);
                                }
                            }
                        }
                    }
                }
                if (kept > 0) {
                    System.out.println("  Keeping " + kept + " Zulip messages unread (DMs, followed topics, starred/alert words, "
                            + "or about " + String.join("/", keywords) + ")");
                }
                if (!serverWatchIds.isEmpty()) {
                    watchMessageIds.put(server, serverWatchIds);
                }
            } catch (Exception e) {
                System.err.println("  Error fetching Zulip messages from " + server.url() + ": " + e.getMessage());
            }
        }

        return new FetchResult(allMessages, watchMessageIds);
    }

    private static boolean all(List<String> watches) {
        return watches.stream().anyMatch(w -> w.strip().equals(ALL_UNREAD));
    }

    private static Set<Long> ids(List<ZulipMessage> messages) {
        Set<Long> out = new HashSet<>();
        for (ZulipMessage m : messages) out.add(extractMessageId(m.url()));
        return out;
    }

    /**
     * A message involves the principal when it is a DM, sits in a topic they
     * follow, is starred or hits one of their Zulip alert words, or is about
     * one of ZULIP_KEEP_UNREAD's subjects. Mentions never get this far - they
     * are fetched separately and never marked read.
     *
     * Keywords match case-insensitively with spaces, '-' and '_' ignored, so
     * "Dev UI" also matches "dev-ui", "devui" and "#dev_ui".
     */
    static boolean involvesMe(ZulipMessage m, boolean followedTopic, List<String> keywords) {
        if (m.involvesMe() || followedTopic) return true;
        String hay = squash(m.stream() + " " + m.topic() + " " + m.fullText());
        for (String k : keywords) {
            if (!k.isBlank() && hay.contains(squash(k))) return true;
        }
        return false;
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }

    static void markAsRead(Map<Config.ZulipServer, List<Long>> watchMessageIds) {
        for (var entry : watchMessageIds.entrySet()) {
            Config.ZulipServer server = entry.getKey();
            List<Long> ids = entry.getValue();
            if (ids.isEmpty()) continue;

            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()) {

                String baseUrl = server.url().startsWith("https://") ? server.url() : "https://" + server.url();
                baseUrl = baseUrl.replaceAll("/+$", "");

                String auth = Base64.getEncoder().encodeToString(
                        (server.email() + ":" + server.apiKey()).getBytes(StandardCharsets.UTF_8));

                String body = "messages=" + URLEncoder.encode(MAPPER.writeValueAsString(ids), StandardCharsets.UTF_8)
                        + "&op=add&flag=read";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/messages/flags"))
                        .header("Authorization", "Basic " + auth)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    System.out.println("  Marked " + ids.size() + " Zulip messages as read on " + server.url());
                } else {
                    System.err.println("  Failed to mark Zulip messages as read on " + server.url()
                            + ": HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                System.err.println("  Error marking Zulip messages as read on " + server.url()
                        + ": " + e.getMessage());
            }
        }
    }

    private static List<ZulipMessage> fetchMessages(Config.ZulipServer server, String narrow) {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {

            String baseUrl = server.url().startsWith("https://") ? server.url() : "https://" + server.url();
            baseUrl = baseUrl.replaceAll("/+$", "");

            String encodedNarrow = URLEncoder.encode(narrow, StandardCharsets.UTF_8);
            String url = baseUrl + "/api/v1/messages?narrow=" + encodedNarrow
                    + "&anchor=newest&num_before=" + MAX_MESSAGES + "&num_after=0"
                    + "&apply_markdown=false";

            String auth = Base64.getEncoder().encodeToString(
                    (server.email() + ":" + server.apiKey()).getBytes(StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + auth)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("  Zulip API returned " + response.statusCode() + " from " + server.url());
                return List.of();
            }

            JsonNode root = MAPPER.readTree(response.body());
            if (!"success".equals(root.path("result").asText())) {
                System.err.println("  Zulip API error: " + root.path("msg").asText("unknown error"));
                return List.of();
            }

            JsonNode messages = root.path("messages");
            if (!messages.isArray()) return List.of();

            List<ZulipMessage> result = new ArrayList<>();
            for (JsonNode msg : messages) {
                boolean dm = "private".equals(msg.path("type").asText());
                Set<String> flags = new HashSet<>();
                msg.path("flags").forEach(f -> flags.add(f.asText()));
                boolean personal = dm || flags.contains("starred") || flags.contains("has_alert_word")
                        || flags.contains("mentioned") || flags.contains("wildcard_mentioned");
                String stream = dm ? "DM" : msg.path("display_recipient").asText("");
                String topic = msg.path("subject").asText("");
                String sender = msg.path("sender_full_name").asText("");
                String content = msg.path("content").asText("");
                String fullText = content;
                long messageId = msg.path("id").asLong();

                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }

                String messageUrl = buildMessageUrl(baseUrl, stream, topic, messageId);

                result.add(new ZulipMessage(server.url(), stream, topic, sender, content, messageUrl, false,
                        personal, fullText));
            }
            return result;
        } catch (Exception e) {
            System.err.println("  Error calling Zulip API: " + e.getMessage());
            return List.of();
        }
    }

    private static String buildWatchNarrow(String watch) {
        String[] parts = watch.split(":", 2);
        String stream = parts[0].strip();
        if (stream.isEmpty()) return null;

        StringBuilder narrow = new StringBuilder();
        narrow.append("[{\"operator\":\"stream\",\"operand\":\"")
                .append(escapeJson(stream))
                .append("\"},{\"operator\":\"is\",\"operand\":\"unread\"}");

        if (parts.length > 1) {
            String topic = parts[1].strip();
            if (!topic.isEmpty()) {
                narrow.append(",{\"operator\":\"topic\",\"operand\":\"")
                        .append(escapeJson(topic))
                        .append("\"}");
            }
        }

        narrow.append("]");
        return narrow.toString();
    }

    private static String buildMessageUrl(String baseUrl, String stream, String topic, long messageId) {
        String encodedStream = URLEncoder.encode(stream, StandardCharsets.UTF_8).replace("+", ".");
        String encodedTopic = URLEncoder.encode(topic, StandardCharsets.UTF_8).replace("+", ".");
        return baseUrl + "/#narrow/stream/" + encodedStream + "/topic/" + encodedTopic + "/near/" + messageId;
    }

    private static long extractMessageId(String url) {
        int nearIdx = url.lastIndexOf("/near/");
        if (nearIdx < 0) return -1;
        try {
            return Long.parseLong(url.substring(nearIdx + 6));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
