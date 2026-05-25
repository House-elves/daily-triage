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

    record ZulipMessage(String server, String stream, String topic, String sender,
                        String content, String url, boolean isMention) {}

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
                        .map(m -> new ZulipMessage(m.server(), m.stream(), m.topic(), m.sender(), m.content(), m.url(), true))
                        .toList());

                Set<Long> mentionIds = new HashSet<>();
                for (ZulipMessage m : mentions) {
                    mentionIds.add(extractMessageId(m.url()));
                }

                List<Long> serverWatchIds = new ArrayList<>();
                for (String watch : watches) {
                    String narrow = buildWatchNarrow(watch);
                    if (narrow == null) continue;

                    List<ZulipMessage> watchMessages = fetchMessages(server, narrow);
                    for (ZulipMessage m : watchMessages) {
                        long id = extractMessageId(m.url());
                        if (!mentionIds.contains(id)) {
                            allMessages.add(m);
                            serverWatchIds.add(id);
                        }
                    }
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
                    System.out.println("  Marked " + ids.size() + " watched Zulip messages as read on " + server.url());
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
                String stream = msg.path("display_recipient").asText("");
                String topic = msg.path("subject").asText("");
                String sender = msg.path("sender_full_name").asText("");
                String content = msg.path("content").asText("");
                long messageId = msg.path("id").asLong();

                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }

                String messageUrl = buildMessageUrl(baseUrl, stream, topic, messageId);

                result.add(new ZulipMessage(server.url(), stream, topic, sender, content, messageUrl, false));
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
