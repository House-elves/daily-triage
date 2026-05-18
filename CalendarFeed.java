import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.regex.Pattern;

public class CalendarFeed {

    private static final DateTimeFormatter ICAL_DATETIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter ICAL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern UNFOLD = Pattern.compile("\\r?\\n[ \\t]");

    record CalendarEvent(String summary, String time, String location) {}

    // ── Public API ──

    static List<CalendarEvent> fetchEvents(List<String> urls) {
        if (urls.isEmpty()) return List.of();

        LocalDate today = LocalDate.now();
        List<CalendarEvent> allEvents = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (String url : urls) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(java.time.Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                List<CalendarEvent> events = parseIcalFeed(response.body(), today);
                allEvents.addAll(events);
            } catch (Exception e) {
                System.err.println("  Error fetching calendar: " + e.getMessage());
            }
        }

        allEvents.sort(Comparator
                .<CalendarEvent, Boolean>comparing(e -> e.time().contains("All"))
                .thenComparing(CalendarEvent::time));
        return allEvents;
    }

    // ── iCal parser ──

    static List<CalendarEvent> parseIcalFeed(String text, LocalDate targetDate) {
        text = UNFOLD.matcher(text).replaceAll("");
        List<CalendarEvent> events = new ArrayList<>();

        boolean inEvent = false;
        String dtstartRaw = null;
        String dtendRaw = null;
        String summary = null;
        String location = null;
        String rruleStr = null;
        List<LocalDate> exdates = null;

        for (String line : text.split("\\r?\\n")) {
            line = line.strip();

            if ("BEGIN:VEVENT".equals(line)) {
                inEvent = true;
                dtstartRaw = null;
                dtendRaw = null;
                summary = null;
                location = null;
                rruleStr = null;
                exdates = new ArrayList<>();
                continue;
            }

            if ("END:VEVENT".equals(line)) {
                inEvent = false;
                LocalDateTime dtstartDt = parseIcalDt(dtstartRaw);
                Map<String, String> rrule = rruleStr != null ? parseRrule(rruleStr) : null;

                if (eventOccursOn(dtstartDt, rrule, exdates != null ? exdates : List.of(), targetDate)) {
                    boolean isAllDay = dtstartRaw != null && dtstartRaw.split(":")[0].contains("VALUE=DATE");
                    String timeStr;

                    if (isAllDay) {
                        timeStr = "All day";
                    } else {
                        String startTime = dtstartDt != null ? dtstartDt.format(DateTimeFormatter.ofPattern("HH:mm")) : "";
                        LocalDateTime endDt = parseIcalDt(dtendRaw);
                        String endTime = endDt != null ? endDt.format(DateTimeFormatter.ofPattern("HH:mm")) : "";
                        timeStr = !endTime.isEmpty() ? startTime + " - " + endTime : startTime;
                    }

                    events.add(new CalendarEvent(
                            summary != null ? summary : "(no title)",
                            timeStr,
                            location != null ? location : ""));
                }
                continue;
            }

            if (!inEvent) continue;

            if (line.startsWith("DTSTART")) {
                dtstartRaw = line;
            } else if (line.startsWith("DTEND")) {
                dtendRaw = line;
            } else if (line.startsWith("SUMMARY:")) {
                summary = line.substring(8);
            } else if (line.startsWith("LOCATION:")) {
                location = line.substring(9);
            } else if (line.startsWith("RRULE:")) {
                rruleStr = line.substring(6);
            } else if (line.startsWith("EXDATE")) {
                LocalDateTime exDt = parseIcalDt(line);
                if (exDt != null && exdates != null) {
                    exdates.add(exDt.toLocalDate());
                }
            }
        }

        events.sort(Comparator
                .<CalendarEvent, Boolean>comparing(e -> e.time().contains("All"))
                .thenComparing(CalendarEvent::time));
        return events;
    }

    // ── Date/time parsing ──

    static LocalDateTime parseIcalDt(String propLine) {
        if (propLine == null) return null;
        String value = propLine.substring(propLine.lastIndexOf(':') + 1).strip();
        if (value.endsWith("Z")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            return LocalDateTime.parse(value, ICAL_DATETIME);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDate.parse(value, ICAL_DATE).atStartOfDay();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    // ── RRULE parsing ──

    static Map<String, String> parseRrule(String rruleStr) {
        Map<String, String> parts = new HashMap<>();
        for (String part : rruleStr.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                parts.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return parts;
    }

    private static final Map<String, DayOfWeek> DAY_MAP = Map.of(
            "MO", DayOfWeek.MONDAY, "TU", DayOfWeek.TUESDAY,
            "WE", DayOfWeek.WEDNESDAY, "TH", DayOfWeek.THURSDAY,
            "FR", DayOfWeek.FRIDAY, "SA", DayOfWeek.SATURDAY,
            "SU", DayOfWeek.SUNDAY
    );

    // ── COUNT to end date ──

    static LocalDate countToEndDate(LocalDate startDate, Map<String, String> rrule) {
        String freq = rrule.getOrDefault("FREQ", "");
        int count = Integer.parseInt(rrule.getOrDefault("COUNT", "0"));
        if (count <= 0) return null;
        int interval = Integer.parseInt(rrule.getOrDefault("INTERVAL", "1"));

        switch (freq) {
            case "DAILY":
                return startDate.plusDays((long) (count - 1) * interval);

            case "WEEKLY":
                String byday = rrule.getOrDefault("BYDAY", "");
                if (!byday.isEmpty()) {
                    List<DayOfWeek> allowed = new ArrayList<>();
                    for (String d : byday.split(",")) {
                        String dayStr = d.strip();
                        if (dayStr.length() >= 2) {
                            DayOfWeek dow = DAY_MAP.get(dayStr.substring(dayStr.length() - 2));
                            if (dow != null) allowed.add(dow);
                        }
                    }
                    Collections.sort(allowed);
                    if (allowed.isEmpty()) {
                        return startDate.plusWeeks((long) (count - 1) * interval);
                    }
                    int occurrences = 0;
                    LocalDate d = startDate;
                    while (occurrences < count) {
                        if (allowed.contains(d.getDayOfWeek()) && !d.isBefore(startDate)) {
                            occurrences++;
                            if (occurrences >= count) return d;
                        }
                        d = d.plusDays(1);
                    }
                    return d;
                }
                return startDate.plusWeeks((long) (count - 1) * interval);

            case "MONTHLY":
                int totalMonths = (count - 1) * interval;
                return startDate.plusMonths(totalMonths);

            case "YEARLY":
                return startDate.plusYears((long) (count - 1) * interval);

            default:
                return null;
        }
    }

    // ── Does event occur on target date? ──

    static boolean eventOccursOn(LocalDateTime dtstartDt, Map<String, String> rrule,
                                  List<LocalDate> exdates, LocalDate target) {
        if (dtstartDt == null) return false;

        LocalDate startDate = dtstartDt.toLocalDate();
        if (exdates.contains(target)) return false;

        // No recurrence rule — simple date match
        if (rrule == null) {
            return startDate.equals(target);
        }

        if (target.isBefore(startDate)) return false;

        // Check UNTIL
        String untilStr = rrule.get("UNTIL");
        if (untilStr != null) {
            untilStr = untilStr.replace("Z", "");
            LocalDate untilDate = null;
            try {
                untilDate = LocalDateTime.parse(untilStr, ICAL_DATETIME).toLocalDate();
            } catch (DateTimeParseException e1) {
                try {
                    untilDate = LocalDate.parse(untilStr, ICAL_DATE);
                } catch (DateTimeParseException e2) {
                    // ignore
                }
            }
            if (untilDate != null && target.isAfter(untilDate)) return false;
        }

        // Check COUNT
        if (rrule.containsKey("COUNT")) {
            LocalDate endDate = countToEndDate(startDate, rrule);
            if (endDate != null && target.isAfter(endDate)) return false;
        }

        int interval = Integer.parseInt(rrule.getOrDefault("INTERVAL", "1"));
        String freq = rrule.getOrDefault("FREQ", "");

        switch (freq) {
            case "DAILY":
                long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startDate, target);
                return daysBetween % interval == 0;

            case "WEEKLY":
                String byday = rrule.getOrDefault("BYDAY", "");
                if (!byday.isEmpty()) {
                    List<DayOfWeek> allowed = new ArrayList<>();
                    for (String d : byday.split(",")) {
                        String dayStr = d.strip();
                        if (dayStr.length() >= 2) {
                            DayOfWeek dow = DAY_MAP.get(dayStr.substring(dayStr.length() - 2));
                            if (dow != null) allowed.add(dow);
                        }
                    }
                    if (!allowed.contains(target.getDayOfWeek())) return false;
                } else if (target.getDayOfWeek() != startDate.getDayOfWeek()) {
                    return false;
                }
                if (interval > 1) {
                    long weeksDiff = java.time.temporal.ChronoUnit.DAYS.between(startDate, target) / 7;
                    if (weeksDiff % interval != 0) return false;
                }
                return true;

            case "MONTHLY":
                if (interval > 1) {
                    int monthsDiff = (target.getYear() - startDate.getYear()) * 12
                            + (target.getMonthValue() - startDate.getMonthValue());
                    if (monthsDiff % interval != 0) return false;
                }
                String bymonthday = rrule.get("BYMONTHDAY");
                if (bymonthday != null) {
                    return target.getDayOfMonth() == Integer.parseInt(bymonthday);
                }
                String monthlybyday = rrule.getOrDefault("BYDAY", "");
                if (!monthlybyday.isEmpty()) {
                    for (String d : monthlybyday.split(",")) {
                        d = d.strip();
                        String weekdayStr = d.substring(d.length() - 2);
                        DayOfWeek dow = DAY_MAP.get(weekdayStr);
                        if (dow == null || target.getDayOfWeek() != dow) continue;
                        String prefix = d.substring(0, d.length() - 2);
                        if (!prefix.isEmpty()) {
                            int n = Integer.parseInt(prefix);
                            if (n > 0) {
                                int occurrence = (target.getDayOfMonth() - 1) / 7 + 1;
                                if (occurrence == n) return true;
                            } else if (n == -1) {
                                int daysInMonth = YearMonth.of(target.getYear(), target.getMonth()).lengthOfMonth();
                                if (target.getDayOfMonth() > daysInMonth - 7) return true;
                            }
                        } else {
                            return true;
                        }
                    }
                    return false;
                }
                return target.getDayOfMonth() == startDate.getDayOfMonth();

            case "YEARLY":
                if (target.getMonthValue() == startDate.getMonthValue()
                        && target.getDayOfMonth() == startDate.getDayOfMonth()) {
                    return (target.getYear() - startDate.getYear()) % interval == 0;
                }
                return false;

            default:
                return false;
        }
    }
}
