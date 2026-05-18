import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GitHubIssues {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record IssueInfo(String repo, int number, String title, String url, String labels) {}

    static List<IssueInfo> fetch(Config config) {
        if (config.githubUser == null || config.githubUser.isEmpty()) {
            return List.of();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "gh", "search", "issues",
                    "--assignee", config.githubUser,
                    "--state", "open",
                    "--limit", "30",
                    "--json", "repository,title,url,number,updatedAt,labels"
            );
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return List.of();
            }

            String stdout = new String(process.getInputStream().readAllBytes()).trim();
            if (process.exitValue() != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes()).trim();
                System.err.println("  Error fetching GitHub issues: "
                        + stderr.substring(0, Math.min(200, stderr.length())));
                return List.of();
            }

            JsonNode arr = MAPPER.readTree(stdout);
            if (!arr.isArray()) return List.of();

            Map<String, Boolean> archivedCache = new HashMap<>();
            List<IssueInfo> filtered = new ArrayList<>();

            for (JsonNode issue : arr) {
                String repo = issue.path("repository").path("nameWithOwner").asText("");
                String org = repo.contains("/") ? repo.split("/")[0] : "";

                if (config.excludeOrgs.contains(org)) continue;
                if (isArchived(repo, archivedCache)) continue;

                StringBuilder labels = new StringBuilder();
                JsonNode labelsNode = issue.path("labels");
                if (labelsNode.isArray()) {
                    for (int i = 0; i < labelsNode.size(); i++) {
                        if (i > 0) labels.append(", ");
                        labels.append(labelsNode.get(i).path("name").asText(""));
                    }
                }

                filtered.add(new IssueInfo(
                        repo,
                        issue.path("number").asInt(),
                        issue.path("title").asText(""),
                        issue.path("url").asText(""),
                        labels.toString()
                ));
            }

            return filtered;
        } catch (Exception e) {
            System.err.println("  Error fetching GitHub issues: " + e.getMessage());
            return List.of();
        }
    }

    private static boolean isArchived(String repoName, Map<String, Boolean> cache) {
        return cache.computeIfAbsent(repoName, name -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "gh", "repo", "view", name,
                        "--json", "isArchived", "-q", ".isArchived"
                );
                Process p = pb.start();
                boolean finished = p.waitFor(10, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    return false;
                }
                return p.exitValue() == 0
                        && "true".equals(new String(p.getInputStream().readAllBytes()).trim());
            } catch (Exception e) {
                return false;
            }
        });
    }
}
