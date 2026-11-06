package org.mytpu.assistant.normalize;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Распознавание аудитории и корпуса ТПУ.
 * Примеры: «ауд 403б», «к.10 ауд 402», «10 корпус 101», «403б».
 */
public final class RoomParser {

    private static final int FLAGS =
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern BUILDING_PREFIX = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:корп(?:ус[а-яё]*)?\\.?|к\\.|к\\s+)\\s*([0-9]+[а-яёa-z]?|[гg][кk])(?![\\p{L}\\p{N}])",
            FLAGS
    );
    private static final Pattern BUILDING_SUFFIX = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([0-9]+[а-яёa-z]?|[гg][кk])\\s*(?:-(?:й|го|м|ом))?\\s*(?:корп(?:ус[а-яё]*)?\\.?|к\\.)(?![\\p{L}\\p{N}])",
            FLAGS
    );
    private static final Pattern BUILDING_GK = Pattern.compile(
            "(?<![\\p{L}\\p{N}])([гg][кk])(?![\\p{L}\\p{N}])",
            FLAGS
    );

    // Аудитория с явным маркером «ауд» / «аудитория»
    private static final Pattern EXPLICIT_ROOM = Pattern.compile(
            "(?<![\\p{L}\\p{N}])ауд(?:итори[а-яё]*)?\\.?\\s*([0-9]{1,4})\\s*[-]?\\s*([а-яёa-z])?(?![\\p{L}\\p{N}])",
            FLAGS
    );

    // Номер аудитории с буквой без маркера (например «403б», «403b»)
    private static final Pattern BARE_ROOM_WITH_LETTER = Pattern.compile(
            "(?<![\\p{L}\\p{N}-])([0-9]{3,4})\\s*[-]?\\s*([а-яёa-z])(?![\\p{L}\\p{N}])",
            FLAGS
    );

    // Номер аудитории рядом с корпусом (например «10 корпус 101»)
    private static final Pattern ROOM_NEAR_BUILDING = Pattern.compile(
            "(?<![\\p{L}\\p{N}-])([0-9]{1,4})\\s*[-]?\\s*([а-яёa-z])?(?![\\p{L}\\p{N}])",
            FLAGS
    );

    private RoomParser() {}

    public static Optional<Room> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        String building = null;
        int buildingStart = -1;
        int buildingEnd = -1;

        Matcher suffixMatcher = BUILDING_SUFFIX.matcher(text);
        if (suffixMatcher.find()) {
            building = normalizeBuilding(suffixMatcher.group(1));
            buildingStart = suffixMatcher.start();
            buildingEnd = suffixMatcher.end();
        } else {
            Matcher prefixMatcher = BUILDING_PREFIX.matcher(text);
            if (prefixMatcher.find()) {
                building = normalizeBuilding(prefixMatcher.group(1));
                buildingStart = prefixMatcher.start();
                buildingEnd = prefixMatcher.end();
            } else {
                Matcher gkMatcher = BUILDING_GK.matcher(text);
                if (gkMatcher.find() && EXPLICIT_ROOM.matcher(text).find()) {
                    building = "ГК";
                    buildingStart = gkMatcher.start();
                    buildingEnd = gkMatcher.end();
                }
            }
        }

        // Текст без найденного корпуса для поиска аудитории
        String textForRoom = text;
        if (building != null && buildingStart >= 0) {
            textForRoom = text.substring(0, buildingStart) + " " + text.substring(buildingEnd);
        }

        // 1. Поиск с явным маркером («ауд 402», «ауд 403б»)
        Matcher explicitMatcher = EXPLICIT_ROOM.matcher(textForRoom);
        if (explicitMatcher.find()) {
            String roomNum = formatRoom(explicitMatcher.group(1), explicitMatcher.group(2));
            return Optional.of(new Room(building, roomNum));
        }

        // 2. Если корпус найден, аудиторией может быть число без маркера («10 корпус 101»)
        if (building != null) {
            Matcher nearBuildingMatcher = ROOM_NEAR_BUILDING.matcher(textForRoom);
            while (nearBuildingMatcher.find()) {
                String digits = nearBuildingMatcher.group(1);
                String letter = nearBuildingMatcher.group(2);
                String roomNum = formatRoom(digits, letter);
                return Optional.of(new Room(building, roomNum));
            }
        }

        // 3. Без корпуса: номер 3-4 цифры с обязательной буквой («403б», «403b»)
        Matcher bareMatcher = BARE_ROOM_WITH_LETTER.matcher(textForRoom);
        if (bareMatcher.find()) {
            String roomNum = formatRoom(bareMatcher.group(1), bareMatcher.group(2));
            return Optional.of(new Room(building, roomNum));
        }

        return Optional.empty();
    }

    private static String normalizeBuilding(String b) {
        if (b == null) {
            return null;
        }
        String trimmed = b.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("гк") || lower.equals("gk")) {
            return "ГК";
        }
        return trimmed;
    }

    private static String formatRoom(String digits, String letter) {
        if (letter == null || letter.isBlank()) {
            return digits;
        }
        char c = letter.trim().charAt(0);
        char cyr = switch (Character.toLowerCase(c)) {
            case 'a', 'а' -> 'а';
            case 'b', 'б' -> 'б';
            case 'v', 'в' -> 'в';
            case 'g', 'г' -> 'г';
            case 'd', 'д' -> 'д';
            case 'e', 'е' -> 'е';
            case 'k', 'к' -> 'к';
            case 'm', 'м' -> 'м';
            case 'h', 'н' -> 'н';
            case 'p', 'р' -> 'р';
            case 'c', 'с' -> 'с';
            case 't', 'т' -> 'т';
            case 'x', 'х' -> 'х';
            default -> Character.toLowerCase(c);
        };
        return digits + cyr;
    }
}
