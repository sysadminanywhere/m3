package com.sysadminanywhere.m3.base.i18n;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TranslationsTest {
    @Test void sharedCatalogLocalizesCapacityAndOptionalCatalogLocalizesLicenseUi() {
        var translations = new Translations();
        var russian = Locale.forLanguageTag("ru");
        assertThat(translations.getTranslation("{0} / {1} worker slots", russian, 0, 1))
                .isEqualTo("0 / 1 слотов воркеров");
        String license = translations.getTranslation("Signed license", russian);
        if ("full".equals(System.getProperty("m3.distribution", "community"))) {
            assertThat(license).isNotEqualTo("Signed license");
        } else {
            assertThat(license).isEqualTo("Signed license");
        }
    }
}
