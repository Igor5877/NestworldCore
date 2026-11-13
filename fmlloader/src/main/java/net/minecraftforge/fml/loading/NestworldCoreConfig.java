package net.minecraftforge.fml.loading;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * NestWorld Core configuration file {@code config/nestworld/core.toml} (owner 2026-11-13: administration by a human, not
 * by JVM {@code -D} arguments; Core updates must never require rewriting the file).
 *
 * <ul>
 * <li>Loaded in {@link FMLServiceProvider#initialize} — before Mixin config plugins, {@code Main.main} and any Core class —
 *     so every existing {@code System.getProperty("nestworld.*")} read sees the file's values unchanged.</li>
 * <li>Precedence: a {@code -D} on the command line wins over the file (emergency override); the file wins over the
 *     built-in default.</li>
 * <li>First start: the file is generated from the bundled schema ({@code /nestworld/core-config-schema.tsv}) with every
 *     parameter documented and commented out ({@code # key = default}); values already given by {@code -D} are written as
 *     active lines (migration — the {@code -D} can then be removed).</li>
 * <li>Core update: parameters the file does not mention yet are APPENDED in a dated block; nothing the administrator
 *     wrote is ever rewritten or removed (a timestamped backup is kept before every append). Unknown keys in the file
 *     (removed / misspelt) are reported, not deleted.</li>
 * <li>Every start writes {@code config/nestworld/core-effective.txt}: each parameter's effective value and its source
 *     (JVM / FILE / DEFAULT). {@code /nestworld config} shows it in game.</li>
 * </ul>
 * Format: a simple TOML-like subset — {@code [section]} headers (grouping only), {@code key = value} lines, {@code #}
 * comments; keys are written WITHOUT the {@code nestworld.} prefix and may contain dots. Never throws: any failure is
 * logged and the server starts with the previous behaviour.
 */
public final class NestworldCoreConfig {
    private NestworldCoreConfig() {}

    static final String PREFIX = "nestworld.";
    static final String SOURCE_PREFIX = "nestworld.config.source.";
    private static final Pattern LINE = Pattern.compile("^\\s*(#\\s*)?([A-Za-z0-9_.\\-]+)\\s*=\\s*(.*?)\\s*$");

    record Key(String name, String type, String def, boolean restart, String section, String purpose) {}

    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();
    static {
        SECTIONS.put("regions", "Регіони й потоки");
        SECTIONS.put("regions_split", "Розділення регіонів (split)");
        SECTIONS.put("entities", "Сутності й гравці");
        SECTIONS.put("chunks", "Чанки й генерація світу");
        SECTIONS.put("network", "Мережа");
        SECTIONS.put("safety", "Безпека й watchdog");
        SECTIONS.put("diagnostics", "Діагностика й логи");
        SECTIONS.put("compat", "Сумісність модів (ядро)");
        SECTIONS.put("ae2", "AE2 (аддон nestworld-compat-ae2)");
        SECTIONS.put("mekanism", "Mekanism (аддон nestworld-compat-mekanism)");
        SECTIONS.put("mm", "MasterfulMachinery (аддон nestworld-compat-mm)");
        SECTIONS.put("experimental", "Експериментальне й тестове — на live НЕ змінювати без потреби");
        SECTIONS.put("other", "Інше");
    }

    public static void load(Path configDir, Logger log) {
        try {
            List<Key> schema = readSchema();
            Path dir = configDir.resolve("nestworld");
            Files.createDirectories(dir);
            Path file = dir.resolve("core.toml");
            if (!Files.exists(file)) {
                Files.writeString(file, generate(schema), StandardCharsets.UTF_8);
                log.info("NestWorld: створено {} ({} параметрів)", file, schema.size());
            } else {
                appendNew(file, schema, log);
            }
            apply(file, schema, dir.resolve("core-effective.txt"), log);
        } catch (Throwable t) {
            log.error("NestWorld: core.toml не застосовано (сервер стартує з аргументами JVM / типовими значеннями)", t);
        }
    }

    // ------------------------------------------------------------------ schema

    static List<Key> readSchema() throws IOException {
        List<Key> out = new ArrayList<>();
        try (InputStream in = NestworldCoreConfig.class.getResourceAsStream("/nestworld/core-config-schema.tsv")) {
            if (in == null) throw new IOException("schema resource /nestworld/core-config-schema.tsv missing");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t", -1);
                if (c.length < 6) continue;
                out.add(new Key(c[0].trim(), c[1].trim(), c[2].trim(), !"no".equalsIgnoreCase(c[3].trim()),
                        SECTIONS.containsKey(c[4].trim()) ? c[4].trim() : "other", c[5].trim()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ generate / append

    static String header() {
        return """
                # ============================================================================================
                #  NestWorld Core — налаштування ядра
                # ============================================================================================
                #  Як користуватися:
                #   * Рядок "# ключ = значення" — параметр НЕ заданий, діє типове значення ядра.
                #     Щоб змінити: приберіть "# " на початку рядка й поставте своє значення.
                #   * Більшість параметрів діють лише після рестарту сервера (це вказано біля кожного).
                #   * Аргумент JVM -Dnestworld.<ключ>=... має пріоритет над цим файлом (для аварійних обходів).
                #   * Після оновлення ядра нові параметри ДОПИСУЮТЬСЯ в кінець цього файлу; ваші значення й
                #     коментарі ядро ніколи не змінює. Перед кожним дописуванням робиться резервна копія.
                #   * Фактичні значення після старту: config/nestworld/core-effective.txt
                #     або в грі: /nestworld config
                #   * Значення: true/false, числа, або текст (лапки необов'язкові).
                # ============================================================================================
                """;
    }

    static String generate(List<Key> schema) {
        StringBuilder sb = new StringBuilder(header());
        for (String sec : SECTIONS.keySet()) {
            List<Key> keys = schema.stream().filter(k -> k.section.equals(sec)).toList();
            if (keys.isEmpty()) continue;
            sb.append("\n[").append(sec).append("]   # ").append(SECTIONS.get(sec)).append("\n");
            for (Key k : keys) appendKey(sb, k);
        }
        return sb.toString();
    }

    /** A default that is an expression in code (e.g. "Math.max(1, cores - 1)") is computed at runtime. */
    static boolean computed(Key k) {
        if (k.def.isEmpty()) return true;
        return validate(k.def, k.type) != null || (k.def.contains("(") && !k.type.toLowerCase().contains("string"));
    }

    static void appendKey(StringBuilder sb, Key k) {
        sb.append("\n# ").append(k.purpose.isEmpty() ? "Призначення не задокументоване (див. код)" : k.purpose).append('\n');
        sb.append("# тип: ").append(k.type).append(", типово: ").append(computed(k) ? "обчислюється автоматично" : k.def)
                .append(k.restart ? ", зміна діє після рестарту" : ", можна змінювати на ходу").append('\n');
        String jvm = System.getProperty(PREFIX + k.name);
        if (jvm != null) {
            sb.append("# перенесено з аргументу JVM -D").append(PREFIX).append(k.name).append(" (сам -D тепер можна прибрати)\n");
            sb.append(k.name).append(" = ").append(render(jvm, k.type)).append('\n');
        } else {
            sb.append("# ").append(k.name).append(" = ").append(computed(k) ? (k.type.toLowerCase().contains("bool") ? "true" : k.type.toLowerCase().contains("string") ? "\"\"" : "<число>") : render(k.def, k.type)).append('\n');
        }
    }

    static String render(String v, String type) {
        String t = type.toLowerCase();
        if (t.contains("bool") || t.contains("int") || t.contains("long") || t.contains("double") || t.contains("float")) return v;
        return "\"" + v.replace("\"", "\\\"") + "\"";
    }

    static void appendNew(Path file, List<Key> schema, Logger log) throws IOException {
        Set<String> mentioned = new HashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher m = LINE.matcher(line);
            if (m.matches()) mentioned.add(m.group(2));
        }
        List<Key> missing = schema.stream().filter(k -> !mentioned.contains(k.name)).toList();
        if (missing.isEmpty()) return;
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Files.copy(file, file.resolveSibling("core.toml.bak-" + ts), StandardCopyOption.REPLACE_EXISTING);
        StringBuilder sb = new StringBuilder("\n# ============================================================================================\n");
        sb.append("#  Нові параметри ядра — дописано автоматично ").append(ts.replace('_', ' ')).append(" (").append(missing.size())
                .append(")\n#  Ваші налаштування вище не змінювались.\n");
        sb.append("# ============================================================================================\n");
        for (String sec : SECTIONS.keySet()) {
            List<Key> keys = missing.stream().filter(k -> k.section.equals(sec)).toList();
            if (keys.isEmpty()) continue;
            sb.append("\n# --- розділ: ").append(SECTIONS.get(sec)).append(" ---\n");
            for (Key k : keys) appendKey(sb, k);
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        log.info("NestWorld: у core.toml дописано {} нових параметрів (резервна копія core.toml.bak-{})", missing.size(), ts);
    }

    // ------------------------------------------------------------------ apply

    static void apply(Path file, List<Key> schema, Path report, Logger log) throws IOException {
        Map<String, Key> byName = new LinkedHashMap<>();
        for (Key k : schema) byName.put(k.name, k);
        Map<String, String> active = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        int lineNo = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            Matcher m = LINE.matcher(line);
            if (!m.matches() || m.group(1) != null) continue;           // not a key line, or commented out
            String key = m.group(2), raw = stripComment(m.group(3));
            Key k = byName.get(key);
            if (k == null) { problems.add("рядок " + lineNo + ": невідомий або застарілий параметр '" + key + "' — проігноровано"); continue; }
            String v = unquote(raw);
            String err = validate(v, k.type);
            if (err != null) { problems.add("рядок " + lineNo + ": " + key + " = " + raw + " — " + err + " (діє типове)"); continue; }
            active.put(key, v);
        }
        int fromFile = 0, fromJvm = 0;
        StringBuilder rep = new StringBuilder("# NestWorld Core — фактичні налаштування (згенеровано при старті "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + ")\n# ключ\tзначення\tджерело\tрестарт\n");
        for (Key k : schema) {
            String full = PREFIX + k.name;
            String jvm = System.getProperty(full);
            String fileV = active.get(k.name);
            String eff, src;
            if (jvm != null) {
                eff = jvm; src = "JVM";
                fromJvm++;
                if (fileV != null && !fileV.equals(jvm)) problems.add(k.name + ": файл каже " + fileV + ", але діє -D " + jvm + " (аргумент JVM має пріоритет)");
            } else if (fileV != null) {
                System.setProperty(full, fileV);
                eff = fileV; src = "FILE";
                fromFile++;
            } else {
                eff = computed(k) ? "(обчислюється автоматично)" : k.def; src = "DEFAULT";
            }
            System.setProperty(SOURCE_PREFIX + k.name, src);
            rep.append(k.name).append('\t').append(eff).append('\t').append(src).append('\t').append(k.restart ? "так" : "ні").append('\n');
        }
        if (!problems.isEmpty()) {
            rep.append("\n# Попередження:\n");
            for (String p : problems) { rep.append("# ").append(p).append('\n'); log.warn("NestWorld core.toml: {}", p); }
        }
        Files.writeString(report, rep.toString(), StandardCharsets.UTF_8);
        System.setProperty("nestworld.config.file", file.toAbsolutePath().toString());
        System.setProperty("nestworld.config.report", report.toAbsolutePath().toString());
        System.setProperty("nestworld.config.problems", Integer.toString(problems.size()));
        log.info("NestWorld: core.toml застосовано — з файлу {}, з -D {}, попереджень {}", fromFile, fromJvm, problems.size());
    }

    static String stripComment(String raw) {
        boolean q = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') q = !q;
            else if (c == '#' && !q) return raw.substring(0, i).trim();
        }
        return raw.trim();
    }

    static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) return v.substring(1, v.length() - 1).replace("\\\"", "\"");
        return v;
    }

    static String validate(String v, String type) {
        String t = type.toLowerCase();
        try {
            if (t.contains("bool")) return v.equals("true") || v.equals("false") ? null : "очікується true або false";
            if (t.contains("int") || t.contains("long")) { Long.parseLong(v); return null; }
            if (t.contains("double") || t.contains("float")) { Double.parseDouble(v); return null; }
        } catch (NumberFormatException e) {
            return "очікується число";
        }
        return null;
    }
}
