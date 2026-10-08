package com.sysadminanywhere.m3.base.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.i18n.I18NProvider;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;

/** UI translations only: protocol values and user data are never translated. */
@Component
public final class Translations implements I18NProvider {
    public static final List<Locale> LANGUAGES = List.of(Locale.ENGLISH, Locale.forLanguageTag("ru"),
            Locale.GERMAN, Locale.FRENCH, Locale.ITALIAN, Locale.forLanguageTag("pt"));
    private static final Map<String, Map<String, String>> CATALOGS = loadCatalogs();
    private static Map<String, Map<String, String>> loadCatalogs() {
        var catalogs = new HashMap<String, Map<String, String>>();
        var mapper = new ObjectMapper();
        for (var locale : LANGUAGES) {
            String resource = "/i18n/ui_" + locale.getLanguage() + ".json";
            try (var stream = Translations.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IllegalStateException("Missing translations: " + resource);
                var catalog = mapper.readValue(stream, new TypeReference<Map<String, String>>() {});
                try (var extra = Translations.class.getResourceAsStream("/i18n/extra_ui_" + locale.getLanguage() + ".json")) {
                    if (extra != null) catalog.putAll(mapper.readValue(extra, new TypeReference<Map<String, String>>() {}));
                }
                catalogs.put(locale.getLanguage(), Map.copyOf(catalog));
            } catch (IOException error) { throw new IllegalStateException("Invalid translations: " + resource, error); }
        }
        return Map.copyOf(catalogs);
    }
    public static Locale supported(Locale requested) {
        if (requested == null) return Locale.ENGLISH;
        return LANGUAGES.stream().filter(l -> l.getLanguage().equals(requested.getLanguage())).findFirst().orElse(Locale.ENGLISH);
    }
    public static Locale locale() {
        var ui = UI.getCurrent();
        return ui == null ? Locale.ENGLISH : supported(ui.getLocale());
    }
    public static String t(String key, Object... parameters) { return translate(key, locale(), parameters); }
    private static String translate(String key, Locale locale, Object... parameters) {
        if (key == null) return "";
        var catalog = CATALOGS.get(supported(locale).getLanguage());
        String result = catalog.get(key);
        if (result == null) {
            // Parameterized service validation messages are localized only at the UI boundary.
            for (String prefix : List.of("Transformation field is missing: ", "Worker pool already exists: "))
                if (key.startsWith(prefix)) return translate(prefix + "{0}", locale, key.substring(prefix.length()));
            result = key;
        }
        var matcher = java.util.regex.Pattern.compile("\\{(\\d+)\\}").matcher(result);
        var output = new StringBuffer();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String replacement = index < parameters.length ? String.valueOf(parameters[index]) : matcher.group();
            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }
    public static String enumLabel(Enum<?> value) { return value == null ? "" : t(value.name()); }
    @Override public List<Locale> getProvidedLocales() { return LANGUAGES; }
    @Override public String getTranslation(String key, Locale locale, Object... params) { return translate(key, locale, params); }
}
